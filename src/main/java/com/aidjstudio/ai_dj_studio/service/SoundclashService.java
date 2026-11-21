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
import com.aidjstudio.ai_dj_studio.entity.SoundclashBattle;
import com.aidjstudio.ai_dj_studio.entity.SoundclashCandidate;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.repository.SoundclashBattleRepository;
import com.aidjstudio.ai_dj_studio.repository.SoundclashCandidateRepository;
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
                    return Mono.error(new AccessDeniedException("Only the Host or Co-DJ can open a Soundclash battle"));
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
