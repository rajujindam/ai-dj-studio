package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.announcement.PublishSoundclashAnnouncementRequest;
import com.aidjstudio.ai_dj_studio.dto.announcement.PublishTransitionAnnouncementRequest;
import com.aidjstudio.ai_dj_studio.dto.announcement.SoundclashAnnouncementPayload;
import com.aidjstudio.ai_dj_studio.dto.announcement.SoundclashCandidateSummary;
import com.aidjstudio.ai_dj_studio.dto.announcement.TransitionAnnouncementPayload;
import com.aidjstudio.ai_dj_studio.dto.track.RetrievedMetadataEntry;
import com.aidjstudio.ai_dj_studio.dto.transition.TransitionDecisionRequest;
import com.aidjstudio.ai_dj_studio.dto.transition.TransitionDecisionResponse;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipantRole;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.entity.QueueEntry;
import com.aidjstudio.ai_dj_studio.entity.SoundclashBattle;
import com.aidjstudio.ai_dj_studio.entity.SoundclashCandidate;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.repository.QueueEntryRepository;
import com.aidjstudio.ai_dj_studio.repository.SoundclashBattleRepository;
import com.aidjstudio.ai_dj_studio.repository.SoundclashCandidateRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.service.llm.LlmProvider;
import com.aidjstudio.ai_dj_studio.websocket.LoungeSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for publishing AI DJ transition and Soundclash announcements
 * through the existing WebSocket event envelope.
 *
 * Responsibilities:
 * - Orchestrates transition evaluation, grounded banter generation, and WebSocket broadcast.
 * - Orchestrates Soundclash battle winner announcement generation with grounded trivia and WebSocket broadcast.
 * - Enforces lounge membership authorization server-side.
 * - Broadcasts announcements strictly to connected members of the specified lounge.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    public static final String EVENT_ANNOUNCEMENT_TRANSITION = "announcement.transition";
    public static final String EVENT_ANNOUNCEMENT_SOUNDCLASH = "announcement.soundclash";

    private final DjLoungeRepository djLoungeRepository;
    private final LoungeParticipantRepository loungeParticipantRepository;
    private final TrackRepository trackRepository;
    private final QueueEntryRepository queueEntryRepository;
    private final SoundclashBattleRepository soundclashBattleRepository;
    private final SoundclashCandidateRepository soundclashCandidateRepository;
    private final TrackMetadataRetrievalService metadataRetrievalService;
    private final TransitionDecisionService transitionDecisionService;
    private final LlmProvider llmProvider;
    private final LoungeSessionManager loungeSessionManager;

    /**
     * Publishes an AI DJ transition announcement for a lounge.
     * Evaluates candidates from request or the lounge's upcoming queue,
     * retrieves authored metadata, generates grounded banter, and broadcasts to lounge members.
     *
     * @param loungeId             the ID of the lounge
     * @param request              optional candidate IDs and transition constraints
     * @param authenticatedUserId  the user requesting the announcement
     * @return Mono emitting the published transition announcement payload
     */
    public Mono<TransitionAnnouncementPayload> publishTransitionAnnouncement(
            Long loungeId,
            PublishTransitionAnnouncementRequest request,
            Long authenticatedUserId
    ) {
        if (loungeId == null) {
            return Mono.error(new IllegalArgumentException("Lounge ID cannot be null"));
        }
        if (authenticatedUserId == null) {
            return Mono.error(new AccessDeniedException("User must be authenticated"));
        }

        return verifyLoungeAndPermission(loungeId, authenticatedUserId)
                .flatMap(lounge -> {
                    Long currentTrackId = lounge.getCurrentTrackId();
                    if (currentTrackId == null) {
                        return Mono.error(new IllegalStateException("Lounge does not have a current playing track"));
                    }

                    return resolveCandidateTrackIds(loungeId, request)
                            .flatMap(candidateTrackIds -> {
                                if (candidateTrackIds.isEmpty()) {
                                    return Mono.error(new IllegalStateException("No candidate tracks available in queue or request"));
                                }

                                TransitionDecisionRequest decisionRequest = TransitionDecisionRequest.builder()
                                        .candidateTrackIds(candidateTrackIds)
                                        .targetEnergy(request != null ? request.getTargetEnergy() : null)
                                        .bpmTolerancePct(request != null ? request.getBpmTolerancePct() : null)
                                        .build();

                                return transitionDecisionService.evaluateTransition(currentTrackId, decisionRequest)
                                        .flatMap(decision -> {
                                            if (!decision.isSeguePossible()) {
                                                return Mono.error(new IllegalStateException(
                                                        "No candidate track met harmonic and BPM segue constraints: " + decision.getRankingExplanation()
                                                ));
                                            }

                                            return trackRepository.findById(currentTrackId)
                                                    .map(currentTrack -> {
                                                        TransitionAnnouncementPayload payload = TransitionAnnouncementPayload.builder()
                                                                .announcementType("TRANSITION")
                                                                .loungeId(loungeId)
                                                                .currentTrackId(currentTrack.getId())
                                                                .currentTrackTitle(currentTrack.getTitle())
                                                                .currentTrackArtist(currentTrack.getArtist())
                                                                .nextTrackId(decision.getSelectedTrackId())
                                                                .nextTrackTitle(decision.getSelectedTrackTitle())
                                                                .nextTrackArtist(decision.getSelectedTrackArtist())
                                                                .bpmDelta(decision.getBpmDelta())
                                                                .camelotRelation(decision.getCamelotRelation())
                                                                .rankingExplanation(decision.getRankingExplanation())
                                                                .script(decision.getScript())
                                                                .sourceIds(decision.getSourceIds() != null ? decision.getSourceIds() : Collections.emptyList())
                                                                .sources(decision.getSources() != null ? decision.getSources() : Collections.emptyList())
                                                                .createdAt(Instant.now())
                                                                .build();

                                                        broadcastTransitionAnnouncement(loungeId, payload);
                                                        return payload;
                                                    });
                                        });
                            });
                });
    }

    /**
     * Publishes a transition announcement directly from an evaluated transition decision.
     */
    public Mono<TransitionAnnouncementPayload> publishTransitionAnnouncement(
            Long loungeId,
            TransitionDecisionResponse decision,
            Track currentTrack
    ) {
        if (loungeId == null) {
            return Mono.error(new IllegalArgumentException("Lounge ID cannot be null"));
        }
        if (decision == null) {
            return Mono.error(new IllegalArgumentException("Transition decision cannot be null"));
        }

        TransitionAnnouncementPayload payload = TransitionAnnouncementPayload.builder()
                .announcementType("TRANSITION")
                .loungeId(loungeId)
                .currentTrackId(decision.getCurrentTrackId())
                .currentTrackTitle(decision.getCurrentTrackTitle())
                .currentTrackArtist(currentTrack != null ? currentTrack.getArtist() : null)
                .nextTrackId(decision.getSelectedTrackId())
                .nextTrackTitle(decision.getSelectedTrackTitle())
                .nextTrackArtist(decision.getSelectedTrackArtist())
                .bpmDelta(decision.getBpmDelta())
                .camelotRelation(decision.getCamelotRelation())
                .rankingExplanation(decision.getRankingExplanation())
                .script(decision.getScript())
                .sourceIds(decision.getSourceIds() != null ? decision.getSourceIds() : Collections.emptyList())
                .sources(decision.getSources() != null ? decision.getSources() : Collections.emptyList())
                .createdAt(Instant.now())
                .build();

        broadcastTransitionAnnouncement(loungeId, payload);
        return Mono.just(payload);
    }

    /**
     * Publishes a Soundclash victory announcement for a lounge battle with user authorization check.
     */
    public Mono<SoundclashAnnouncementPayload> publishSoundclashAnnouncement(
            Long loungeId,
            Long battleId,
            Long authenticatedUserId
    ) {
        if (loungeId == null) {
            return Mono.error(new IllegalArgumentException("Lounge ID cannot be null"));
        }
        if (authenticatedUserId == null) {
            return Mono.error(new AccessDeniedException("User must be authenticated"));
        }

        return verifyLoungeAndPermission(loungeId, authenticatedUserId)
                .then(publishSoundclashAnnouncement(loungeId, battleId));
    }

    /**
     * Publishes a Soundclash victory announcement for a battle (internal or system call).
     * Retrieves the resolved battle, candidates, authored timeline trivia,
     * generates grounded celebration banter, and broadcasts to lounge members.
     */
    public Mono<SoundclashAnnouncementPayload> publishSoundclashAnnouncement(Long loungeId, Long battleId) {
        if (loungeId == null) {
            return Mono.error(new IllegalArgumentException("Lounge ID cannot be null"));
        }

        Mono<SoundclashBattle> battleMono = (battleId != null)
                ? soundclashBattleRepository.findById(battleId)
                : soundclashBattleRepository.findByLoungeIdOrderByOpenedAtDesc(loungeId).next();

        return battleMono
                .switchIfEmpty(Mono.error(new IllegalStateException("Soundclash battle not found for lounge: " + loungeId)))
                .flatMap(battle -> {
                    if (!"RESOLVED".equalsIgnoreCase(battle.getStatus())) {
                        return Mono.error(new IllegalStateException("Cannot announce an unresolved Soundclash battle"));
                    }
                    if (battle.getWinningTrackId() == null) {
                        return Mono.error(new IllegalStateException("Soundclash battle has no winning track recorded"));
                    }

                    return trackRepository.findById(battle.getWinningTrackId())
                            .switchIfEmpty(Mono.error(new IllegalStateException("Winning track not found with id: " + battle.getWinningTrackId())))
                            .flatMap(winningTrack -> loadCandidatesWithTracks(battle.getId())
                                    .flatMap(candidates -> {
                                        SoundclashCandidateSummary winnerSummary = candidates.stream()
                                                .filter(c -> c.getTrackId().equals(winningTrack.getId()))
                                                .findFirst()
                                                .orElse(null);

                                        int winnerVoteCount = winnerSummary != null && winnerSummary.getVoteCount() != null
                                                ? winnerSummary.getVoteCount()
                                                : 0;

                                        int totalVotes = candidates.stream()
                                                .mapToInt(c -> c.getVoteCount() != null ? c.getVoteCount() : 0)
                                                .sum();

                                        Long winningCandidateId = winnerSummary != null ? winnerSummary.getCandidateId() : null;

                                        return metadataRetrievalService.retrieve(winningTrack.getId(), null, null, 5)
                                                .collectList()
                                                .flatMap(evidence -> {
                                                    List<String> sourceIds = evidence.stream()
                                                            .map(RetrievedMetadataEntry::getSourceId)
                                                            .toList();

                                                    return llmProvider.generateSoundclashScript(winningTrack, winnerVoteCount, totalVotes, evidence)
                                                            .onErrorResume(ex -> {
                                                                log.warn("LLM Soundclash banter generation failed, falling back to deterministic explanation: {}", ex.getMessage());
                                                                String citations = evidence.stream()
                                                                        .map(e -> String.format("[%s]: %s", e.getSourceId(), e.getText()))
                                                                        .collect(Collectors.joining(" "));

                                                                String fallbackScript = citations.isBlank()
                                                                        ? String.format("Soundclash winner decided! \"%s\" by %s won the clash with %d of %d votes!",
                                                                        winningTrack.getTitle(), winningTrack.getArtist(), winnerVoteCount, totalVotes)
                                                                        : String.format("Soundclash winner decided! \"%s\" by %s won the clash with %d of %d votes! %s",
                                                                        winningTrack.getTitle(), winningTrack.getArtist(), winnerVoteCount, totalVotes, citations);

                                                                return Mono.just(fallbackScript);
                                                            })
                                                            .map(script -> {
                                                                SoundclashAnnouncementPayload payload = SoundclashAnnouncementPayload.builder()
                                                                        .announcementType("SOUNDCLASH")
                                                                        .loungeId(loungeId)
                                                                        .battleId(battle.getId())
                                                                        .winningTrackId(winningTrack.getId())
                                                                        .winningTrackTitle(winningTrack.getTitle())
                                                                        .winningTrackArtist(winningTrack.getArtist())
                                                                        .winningCandidateId(winningCandidateId)
                                                                        .winnerVoteCount(winnerVoteCount)
                                                                        .totalVotes(totalVotes)
                                                                        .candidates(candidates)
                                                                        .script(script)
                                                                        .sourceIds(sourceIds)
                                                                        .sources(evidence)
                                                                        .createdAt(Instant.now())
                                                                        .build();

                                                                broadcastSoundclashAnnouncement(loungeId, payload);
                                                                return payload;
                                                            });
                                                });
                                    })
                            );
                });
    }

    private Mono<List<SoundclashCandidateSummary>> loadCandidatesWithTracks(Long battleId) {
        return soundclashCandidateRepository.findByBattleIdOrderByIdAsc(battleId)
                .flatMap(candidate -> trackRepository.findById(candidate.getTrackId())
                        .map(track -> SoundclashCandidateSummary.builder()
                                .candidateId(candidate.getId())
                                .trackId(candidate.getTrackId())
                                .trackTitle(track.getTitle())
                                .trackArtist(track.getArtist())
                                .voteCount(candidate.getVoteCount())
                                .build())
                )
                .collectList();
    }

    private Mono<List<Long>> resolveCandidateTrackIds(Long loungeId, PublishTransitionAnnouncementRequest request) {
        if (request != null && request.getCandidateTrackIds() != null && !request.getCandidateTrackIds().isEmpty()) {
            return Mono.just(request.getCandidateTrackIds());
        }

        return queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId)
                .map(QueueEntry::getTrackId)
                .collectList();
    }

    private Mono<DjLounge> verifyLoungeAndPermission(Long loungeId, Long authenticatedUserId) {
        return djLoungeRepository.findById(loungeId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Lounge not found with id: " + loungeId)))
                .flatMap(lounge -> {
                    if (lounge.getStatus() == LoungeStatus.ENDED) {
                        return Mono.error(new IllegalStateException("Cannot publish announcements in an ended lounge"));
                    }

                    // Host is always authorized
                    if (lounge.getHostUserId().equals(authenticatedUserId)) {
                        return Mono.just(lounge);
                    }

                    // Check active participant membership
                    return loungeParticipantRepository
                            .findByLoungeIdAndUserIdAndLeftAtIsNull(loungeId, authenticatedUserId)
                            .switchIfEmpty(Mono.error(new AccessDeniedException("User is not an active participant in this lounge")))
                            .thenReturn(lounge);
                });
    }

    private void broadcastTransitionAnnouncement(Long loungeId, TransitionAnnouncementPayload payload) {
        WsEventEnvelope<TransitionAnnouncementPayload> envelope = WsEventEnvelope.<TransitionAnnouncementPayload>builder()
                .event(EVENT_ANNOUNCEMENT_TRANSITION)
                .loungeId(loungeId)
                .payload(payload)
                .ts(Instant.now())
                .build();

        loungeSessionManager.broadcast(loungeId, envelope);
    }

    private void broadcastSoundclashAnnouncement(Long loungeId, SoundclashAnnouncementPayload payload) {
        WsEventEnvelope<SoundclashAnnouncementPayload> envelope = WsEventEnvelope.<SoundclashAnnouncementPayload>builder()
                .event(EVENT_ANNOUNCEMENT_SOUNDCLASH)
                .loungeId(loungeId)
                .payload(payload)
                .ts(Instant.now())
                .build();

        loungeSessionManager.broadcast(loungeId, envelope);
    }
}
