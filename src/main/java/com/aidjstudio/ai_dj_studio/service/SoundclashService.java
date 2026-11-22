package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.soundclash.OpenSoundclashBattleRequest;
import com.aidjstudio.ai_dj_studio.dto.soundclash.SoundclashBattleResponse;
import com.aidjstudio.ai_dj_studio.dto.soundclash.SoundclashCandidateResponse;
import com.aidjstudio.ai_dj_studio.dto.track.TrackResponse;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipantRole;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.entity.QueueEntry;
import com.aidjstudio.ai_dj_studio.entity.SoundclashBattle;
import com.aidjstudio.ai_dj_studio.entity.SoundclashCandidate;
import com.aidjstudio.ai_dj_studio.entity.SoundclashVote;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.repository.QueueEntryRepository;
import com.aidjstudio.ai_dj_studio.repository.SoundclashBattleRepository;
import com.aidjstudio.ai_dj_studio.repository.SoundclashCandidateRepository;
import com.aidjstudio.ai_dj_studio.repository.SoundclashVoteRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.websocket.LoungeSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class SoundclashService {

    public static final int MIN_CANDIDATES = 2;
    public static final int MAX_CANDIDATES = 4;
    public static final int DEFAULT_DURATION_SECONDS = 60;
    public static final int MIN_DURATION_SECONDS = 15;
    public static final int MAX_DURATION_SECONDS = 300;

    private final DjLoungeRepository djLoungeRepository;
    private final LoungeParticipantRepository loungeParticipantRepository;
    private final TrackRepository trackRepository;
    private final SoundclashBattleRepository soundclashBattleRepository;
    private final SoundclashCandidateRepository soundclashCandidateRepository;
    private final SoundclashVoteRepository soundclashVoteRepository;
    private final QueueEntryRepository queueEntryRepository;
    private final QueueService queueService;
    private final LoungeQueueMutationSerializer mutationSerializer;
    private final LoungeSessionManager loungeSessionManager;

    /**
     * Opens a new timed Soundclash battle with 2-4 candidate tracks.
     * Only the Host (or Co-DJ) can open a battle.
     */
    public Mono<SoundclashBattleResponse> openBattle(
            Long loungeId,
            OpenSoundclashBattleRequest request,
            Long authenticatedUserId
    ) {
        if (loungeId == null) {
            return Mono.error(new IllegalArgumentException("Lounge ID cannot be null"));
        }
        if (authenticatedUserId == null) {
            return Mono.error(new AccessDeniedException("User must be authenticated"));
        }
        if (request == null || request.getTrackIds() == null) {
            return Mono.error(new IllegalArgumentException("Candidate tracks cannot be null"));
        }

        List<Long> trackIds = request.getTrackIds();
        if (trackIds.size() < MIN_CANDIDATES || trackIds.size() > MAX_CANDIDATES) {
            return Mono.error(new IllegalArgumentException(
                    "Soundclash battle must have between " + MIN_CANDIDATES + " and " + MAX_CANDIDATES + " candidates"
            ));
        }

        // Check duplicate candidates
        Set<Long> uniqueIds = new HashSet<>(trackIds);
        if (uniqueIds.size() != trackIds.size()) {
            return Mono.error(new IllegalArgumentException("Candidate tracks cannot contain duplicates"));
        }

        int duration = (request.getDurationSeconds() == null || request.getDurationSeconds() <= 0)
                ? DEFAULT_DURATION_SECONDS
                : Math.min(Math.max(request.getDurationSeconds(), MIN_DURATION_SECONDS), MAX_DURATION_SECONDS);

        return djLoungeRepository.findById(loungeId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Lounge not found with id: " + loungeId)))
                .flatMap(lounge -> {
                    // Check if Soundclash is enabled in lounge settings
                    if (!Boolean.TRUE.equals(lounge.getSoundclashEnabled())) {
                        return Mono.error(new IllegalStateException("Soundclash is disabled for this lounge"));
                    }

                    // Check lounge status
                    if (lounge.getStatus() == LoungeStatus.ENDED) {
                        return Mono.error(new IllegalStateException("Cannot open Soundclash in an ended lounge"));
                    }

                    // Verify opening permissions (Host or Co-DJ)
                    return verifyHostOrCoDjPermission(lounge, authenticatedUserId)
                            .then(Mono.defer(() -> verifyNoActiveBattle(loungeId)))
                            .then(Mono.defer(() -> verifyAllTracksExist(trackIds)))
                            .then(Mono.defer(() -> createAndPersistBattle(loungeId, trackIds, authenticatedUserId, duration)));
                });
    }

    /**
     * Gets the currently active (OPEN) Soundclash battle for a lounge, if any.
     */
    public Mono<SoundclashBattleResponse> getActiveBattle(Long loungeId) {
        if (loungeId == null) {
            return Mono.error(new IllegalArgumentException("Lounge ID cannot be null"));
        }

        return soundclashBattleRepository.findActiveBattleByLoungeId(loungeId)
                .flatMap(this::loadBattleDetails);
    }

    /**
     * Casts a vote for a candidate in an active Soundclash battle.
     * Prevents duplicate votes per user per battle.
     */
    public Mono<SoundclashBattleResponse> voteCandidate(
            Long loungeId,
            Long candidateId,
            Long authenticatedUserId
    ) {
        if (loungeId == null) {
            return Mono.error(new IllegalArgumentException("Lounge ID cannot be null"));
        }
        if (candidateId == null) {
            return Mono.error(new IllegalArgumentException("Candidate ID cannot be null"));
        }
        if (authenticatedUserId == null) {
            return Mono.error(new AccessDeniedException("User must be authenticated"));
        }

        return djLoungeRepository.findById(loungeId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Lounge not found with id: " + loungeId)))
                .flatMap(lounge -> {
                    if (lounge.getStatus() == LoungeStatus.ENDED) {
                        return Mono.error(new IllegalStateException("Cannot vote in an ended lounge"));
                    }

                    return verifyParticipantPermission(lounge, authenticatedUserId)
                            .then(Mono.defer(() -> soundclashBattleRepository.findActiveBattleByLoungeId(loungeId)
                                    .switchIfEmpty(Mono.error(new IllegalStateException("No active Soundclash battle in this lounge")))
                            ))
                            .flatMap(battle -> {
                                LocalDateTime now = LocalDateTime.now();
                                if (now.isAfter(battle.getClosesAt())) {
                                    return Mono.error(new IllegalStateException("Soundclash battle voting has closed"));
                                }

                                return soundclashVoteRepository.existsByBattleIdAndUserId(battle.getId(), authenticatedUserId)
                                        .flatMap(alreadyVoted -> {
                                            if (Boolean.TRUE.equals(alreadyVoted)) {
                                                return Mono.error(new IllegalStateException("User has already voted in this Soundclash battle"));
                                            }

                                            return soundclashCandidateRepository.findById(candidateId)
                                                    .switchIfEmpty(Mono.error(new IllegalArgumentException("Candidate not found with id: " + candidateId)))
                                                    .flatMap(candidate -> {
                                                        if (!candidate.getBattleId().equals(battle.getId())) {
                                                            return Mono.error(new IllegalArgumentException("Candidate does not belong to active battle"));
                                                        }

                                                        SoundclashVote vote = SoundclashVote.builder()
                                                                .battleId(battle.getId())
                                                                .candidateId(candidateId)
                                                                .userId(authenticatedUserId)
                                                                .createdAt(LocalDateTime.now())
                                                                .build();

                                                        candidate.setVoteCount(candidate.getVoteCount() + 1);

                                                        return soundclashVoteRepository.save(vote)
                                                                .then(soundclashCandidateRepository.save(candidate))
                                                                .then(loadBattleDetails(battle))
                                                                .doOnNext(updatedBattle -> broadcastSoundclashVoted(loungeId, updatedBattle));
                                                    });
                                        });
                            });
                });
    }

    /**
     * Resolves an active Soundclash battle deterministically at close time.
     * Ties are broken deterministically by submission order (lowest candidate ID).
     * The winning track is spliced into Next Up (position 1) in the queue.
     * Operation is idempotent: repeated resolution calls return the resolved battle without duplicating queue entries.
     */
    public Mono<SoundclashBattleResponse> resolveBattle(Long loungeId, Long authenticatedUserId) {
        if (loungeId == null) {
            return Mono.error(new IllegalArgumentException("Lounge ID cannot be null"));
        }
        if (authenticatedUserId == null) {
            return Mono.error(new AccessDeniedException("User must be authenticated"));
        }

        return mutationSerializer.executeSerialized(loungeId, () -> doResolveBattle(loungeId, authenticatedUserId));
    }

    private Mono<SoundclashBattleResponse> doResolveBattle(Long loungeId, Long authenticatedUserId) {
        return djLoungeRepository.findById(loungeId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Lounge not found with id: " + loungeId)))
                .flatMap(lounge -> {
                    if (lounge.getStatus() == LoungeStatus.ENDED) {
                        return Mono.error(new IllegalStateException("Cannot resolve Soundclash in an ended lounge"));
                    }

                    return verifyHostOrCoDjPermission(lounge, authenticatedUserId)
                            .then(Mono.defer(() -> soundclashBattleRepository.findActiveBattleByLoungeId(loungeId)
                                    .flatMap(activeBattle -> processActiveBattleResolution(loungeId, activeBattle))
                                    .switchIfEmpty(Mono.defer(() -> handleIdempotentResolution(loungeId)))
                            ));
                });
    }

    private Mono<SoundclashBattleResponse> handleIdempotentResolution(Long loungeId) {
        return soundclashBattleRepository.findByLoungeIdOrderByOpenedAtDesc(loungeId)
                .next()
                .flatMap(latestBattle -> {
                    if ("RESOLVED".equalsIgnoreCase(latestBattle.getStatus())) {
                        log.info("Soundclash battle {} in lounge {} is already resolved. Returning idempotent result.",
                                latestBattle.getId(), loungeId);
                        return loadBattleDetails(latestBattle);
                    }
                    return Mono.error(new IllegalStateException("No active or resolvable Soundclash battle found for this lounge"));
                })
                .switchIfEmpty(Mono.error(new IllegalStateException("No active Soundclash battle in this lounge")));
    }

    private Mono<SoundclashBattleResponse> processActiveBattleResolution(Long loungeId, SoundclashBattle battle) {
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(battle.getClosesAt())) {
            return Mono.error(new IllegalStateException("Soundclash battle has not closed yet"));
        }

        return soundclashCandidateRepository.findByBattleIdOrderByIdAsc(battle.getId())
                .collectList()
                .flatMap(candidates -> {
                    if (candidates.isEmpty()) {
                        return Mono.error(new IllegalStateException("Soundclash battle has no candidates"));
                    }

                    // Deterministic ranking:
                    // Primary: voteCount DESC (highest votes win)
                    // Secondary tie-breaker: candidate id ASC (earliest submitted candidate wins)
                    List<SoundclashCandidate> sortedCandidates = new ArrayList<>(candidates);
                    sortedCandidates.sort(
                            Comparator.comparing(SoundclashCandidate::getVoteCount, Comparator.reverseOrder())
                                    .thenComparing(SoundclashCandidate::getId)
                    );

                    SoundclashCandidate winner = sortedCandidates.get(0);
                    battle.setStatus("RESOLVED");
                    battle.setWinningTrackId(winner.getTrackId());

                    return soundclashBattleRepository.save(battle)
                            .flatMap(savedBattle -> spliceWinningTrackIntoQueue(loungeId, winner)
                                    .then(loadBattleDetails(savedBattle))
                                    .flatMap(responseDto -> {
                                        broadcastSoundclashResolved(loungeId, responseDto);
                                        return queueService.broadcastQueueUpdate(loungeId, "soundclash")
                                                .thenReturn(responseDto);
                                    })
                            );
                });
    }

    private Mono<Void> spliceWinningTrackIntoQueue(Long loungeId, SoundclashCandidate winner) {
        return queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId)
                .collectList()
                .flatMap(existingEntries -> {
                    Long winningTrackId = winner.getTrackId();
                    QueueEntry existingEntryForWinner = null;
                    for (QueueEntry entry : existingEntries) {
                        if (entry.getTrackId().equals(winningTrackId)) {
                            existingEntryForWinner = entry;
                            break;
                        }
                    }

                    if (existingEntryForWinner != null) {
                        // Winning track already in queue: move it to position 1 (Next Up)
                        existingEntries.remove(existingEntryForWinner);
                        existingEntries.add(0, existingEntryForWinner);

                        for (int i = 0; i < existingEntries.size(); i++) {
                            existingEntries.get(i).setPosition(i + 1);
                        }

                        return queueEntryRepository.saveAll(existingEntries).then();
                    } else {
                        // Winning track not in queue: shift all existing entries by +1, insert winner at position 1
                        for (QueueEntry entry : existingEntries) {
                            entry.setPosition(entry.getPosition() + 1);
                        }

                        QueueEntry newEntry = new QueueEntry();
                        newEntry.setLoungeId(loungeId);
                        newEntry.setTrackId(winningTrackId);
                        newEntry.setPosition(1);
                        newEntry.setVoteCount(0);
                        newEntry.setAddedByUserId(winner.getSubmittedByUserId());

                        return queueEntryRepository.saveAll(existingEntries)
                                .then(queueEntryRepository.save(newEntry))
                                .then();
                    }
                });
    }

    private Mono<Void> verifyParticipantPermission(DjLounge lounge, Long authenticatedUserId) {
        if (lounge.getHostUserId().equals(authenticatedUserId)) {
            return Mono.empty();
        }

        return loungeParticipantRepository
                .findByLoungeIdAndUserIdAndLeftAtIsNull(lounge.getId(), authenticatedUserId)
                .switchIfEmpty(Mono.error(new AccessDeniedException("User is not an active participant of this lounge")))
                .then();
    }

    private Mono<Void> verifyHostOrCoDjPermission(DjLounge lounge, Long authenticatedUserId) {
        if (lounge.getHostUserId().equals(authenticatedUserId)) {
            return Mono.empty();
        }

        return loungeParticipantRepository
                .findByLoungeIdAndUserIdAndLeftAtIsNull(lounge.getId(), authenticatedUserId)
                .switchIfEmpty(Mono.error(new AccessDeniedException("User is not an active participant of this lounge")))
                .flatMap(participant -> {
                    if (participant.getRole() == LoungeParticipantRole.CO_DJ) {
                        return Mono.empty();
                    }
                    return Mono.error(new AccessDeniedException("Only the Host or Co-DJ can open or resolve a Soundclash battle"));
                });
    }

    private Mono<Void> verifyNoActiveBattle(Long loungeId) {
        return soundclashBattleRepository.findActiveBattleByLoungeId(loungeId)
                .flatMap(existing -> Mono.<Void>error(new IllegalStateException(
                        "An active Soundclash battle (ID: " + existing.getId() + ") is already in progress in this lounge"
                )))
                .then();
    }

    private Mono<Void> verifyAllTracksExist(List<Long> trackIds) {
        return Flux.fromIterable(trackIds)
                .flatMap(trackId -> trackRepository.findById(trackId)
                        .switchIfEmpty(Mono.error(new IllegalArgumentException("Track not found with id: " + trackId)))
                )
                .then();
    }

    private Mono<SoundclashBattleResponse> createAndPersistBattle(
            Long loungeId,
            List<Long> trackIds,
            Long hostUserId,
            int durationSeconds
    ) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime closesAt = now.plusSeconds(durationSeconds);

        SoundclashBattle battle = SoundclashBattle.builder()
                .loungeId(loungeId)
                .openedAt(now)
                .closesAt(closesAt)
                .status("OPEN")
                .winningTrackId(null)
                .build();

        return soundclashBattleRepository.save(battle)
                .flatMap(savedBattle -> {
                    List<SoundclashCandidate> candidates = trackIds.stream()
                            .map(trackId -> SoundclashCandidate.builder()
                                    .battleId(savedBattle.getId())
                                    .trackId(trackId)
                                    .submittedByUserId(hostUserId)
                                    .voteCount(0)
                                    .build())
                            .toList();

                    return soundclashCandidateRepository.saveAll(candidates)
                            .collectList()
                            .flatMap(savedCandidates -> loadBattleDetails(savedBattle))
                            .doOnNext(responseDto -> broadcastSoundclashOpened(loungeId, responseDto));
                });
    }

    private void broadcastSoundclashOpened(Long loungeId, SoundclashBattleResponse responseDto) {
        WsEventEnvelope<SoundclashBattleResponse> envelope = WsEventEnvelope.<SoundclashBattleResponse>builder()
                .event("soundclash.opened")
                .loungeId(loungeId)
                .payload(responseDto)
                .ts(Instant.now())
                .build();

        loungeSessionManager.broadcast(loungeId, envelope);
    }

    private void broadcastSoundclashVoted(Long loungeId, SoundclashBattleResponse responseDto) {
        WsEventEnvelope<SoundclashBattleResponse> envelope = WsEventEnvelope.<SoundclashBattleResponse>builder()
                .event("soundclash.voted")
                .loungeId(loungeId)
                .payload(responseDto)
                .ts(Instant.now())
                .build();

        loungeSessionManager.broadcast(loungeId, envelope);
    }

    private void broadcastSoundclashResolved(Long loungeId, SoundclashBattleResponse responseDto) {
        WsEventEnvelope<SoundclashBattleResponse> envelope = WsEventEnvelope.<SoundclashBattleResponse>builder()
                .event("soundclash.resolved")
                .loungeId(loungeId)
                .payload(responseDto)
                .ts(Instant.now())
                .build();

        loungeSessionManager.broadcast(loungeId, envelope);
    }

    private Mono<SoundclashBattleResponse> loadBattleDetails(SoundclashBattle battle) {
        return soundclashCandidateRepository.findByBattleIdOrderByIdAsc(battle.getId())
                .flatMap(candidate -> trackRepository.findById(candidate.getTrackId())
                        .map(track -> SoundclashCandidateResponse.builder()
                                .id(candidate.getId())
                                .battleId(candidate.getBattleId())
                                .trackId(candidate.getTrackId())
                                .submittedByUserId(candidate.getSubmittedByUserId())
                                .voteCount(candidate.getVoteCount())
                                .track(TrackResponse.fromEntity(track))
                                .build())
                )
                .collectList()
                .map(candidateDtos -> SoundclashBattleResponse.builder()
                        .id(battle.getId())
                        .loungeId(battle.getLoungeId())
                        .openedAt(battle.getOpenedAt())
                        .closesAt(battle.getClosesAt())
                        .status(battle.getStatus())
                        .winningTrackId(battle.getWinningTrackId())
                        .candidates(candidateDtos)
                        .build());
    }
}
