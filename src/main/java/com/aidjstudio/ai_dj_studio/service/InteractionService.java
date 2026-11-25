package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.interaction.InteractionResponse;
import com.aidjstudio.ai_dj_studio.dto.interaction.RecordInteractionRequest;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.Interaction;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.InteractionRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.websocket.LoungeSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Service for capturing user interactions (play duration, completion, likes, co-listening)
 * for recommendation training and taste profile generation.
 *
 * Sensible Event Boundaries:
 * - Minimum play threshold: Discards sub-5-second ticks with no like to prevent spam.
 * - Duration sanity capping: Caps playedSeconds at track duration to prevent fabricated stats.
 * - Anti-fabrication completion: Disallows completion if playedSeconds is less than 15s or < 85% of duration.
 * - Auto-completion: Marks completed = true if playedSeconds >= 85% of track duration.
 * - Recent event debouncing: Consolidates repeated updates within 10 seconds for the same track/user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InteractionService {

    public static final int MIN_MEANINGFUL_PLAYED_SECONDS = 5;
    public static final int MIN_COMPLETION_SECONDS = 15;
    public static final double COMPLETION_PERCENTAGE_THRESHOLD = 0.85;
    public static final long TICK_DEBOUNCE_SECONDS = 10L;

    private final InteractionRepository interactionRepository;
    private final TrackRepository trackRepository;
    private final DjLoungeRepository djLoungeRepository;
    private final LoungeParticipantRepository loungeParticipantRepository;
    private final LoungeSessionManager loungeSessionManager;

    /**
     * Records a track playback interaction for the authenticated user.
     */
    public Mono<InteractionResponse> recordInteraction(RecordInteractionRequest request, Long authenticatedUserId) {
        if (authenticatedUserId == null) {
            return Mono.error(new AccessDeniedException("User must be authenticated to record interactions"));
        }
        if (request == null || request.getTrackId() == null) {
            return Mono.error(new IllegalArgumentException("Track ID is required"));
        }

        return trackRepository.findById(request.getTrackId())
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Track not found with id: " + request.getTrackId())))
                .flatMap(track -> validateLoungeIfPresent(request.getLoungeId())
                        .then(Mono.defer(() -> processInteraction(track, request, authenticatedUserId)))
                );
    }

    /**
     * Records or toggles a like for a track.
     */
    public Mono<InteractionResponse> toggleLike(Long trackId, boolean liked, Long loungeId, Long authenticatedUserId) {
        if (authenticatedUserId == null) {
            return Mono.error(new AccessDeniedException("User must be authenticated to like tracks"));
        }
        if (trackId == null) {
            return Mono.error(new IllegalArgumentException("Track ID is required"));
        }

        return trackRepository.findById(trackId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Track not found with id: " + trackId)))
                .flatMap(track -> validateLoungeIfPresent(loungeId)
                        .then(Mono.defer(() -> doToggleLike(track, liked, loungeId, authenticatedUserId)))
                );
    }

    private Mono<InteractionResponse> doToggleLike(Track track, boolean liked, Long loungeId, Long authenticatedUserId) {
        return interactionRepository.findTopByUserIdAndTrackIdOrderByCreatedAtDesc(authenticatedUserId, track.getId())
                .flatMap(existing -> {
                    existing.setLiked(liked);
                    if (loungeId != null) {
                        existing.setLoungeId(loungeId);
                    }
                    return interactionRepository.save(existing)
                            .map(saved -> InteractionResponse.fromEntity(saved, track));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    Interaction newInteraction = Interaction.builder()
                            .userId(authenticatedUserId)
                            .trackId(track.getId())
                            .loungeId(loungeId)
                            .playedSeconds(0)
                            .completed(false)
                            .liked(liked)
                            .createdAt(LocalDateTime.now())
                            .build();
                    return interactionRepository.save(newInteraction)
                            .map(saved -> InteractionResponse.fromEntity(saved, track));
                }));
    }

    /**
     * Records co-listening interactions for all active participants in a lounge when a track finishes or advances.
     */
    public Flux<InteractionResponse> recordCoListening(Long loungeId, Long trackId, Integer playedSeconds, Boolean completed) {
        if (loungeId == null || trackId == null) {
            return Flux.empty();
        }

        return djLoungeRepository.findById(loungeId)
                .flatMapMany(lounge -> trackRepository.findById(trackId)
                        .flatMapMany(track -> {
                            int trackDuration = track.getDurationSeconds() != null ? track.getDurationSeconds() : 180;
                            int effectiveSeconds = (playedSeconds != null && playedSeconds >= 0)
                                    ? Math.min(playedSeconds, trackDuration)
                                    : trackDuration;
                            boolean isCompleted = (completed != null)
                                    ? completed
                                    : (effectiveSeconds >= (int) (trackDuration * COMPLETION_PERCENTAGE_THRESHOLD));

                            return loungeParticipantRepository.findByLoungeIdAndLeftAtIsNullOrderByJoinedAtAsc(loungeId)
                                    .map(LoungeParticipant::getUserId)
                                    .collectList()
                                    .flatMapMany(participantUserIds -> {
                                        Set<Long> coListeners = new HashSet<>(participantUserIds);
                                        if (lounge.getHostUserId() != null) {
                                            coListeners.add(lounge.getHostUserId());
                                        }

                                        if (coListeners.isEmpty()) {
                                            return Flux.empty();
                                        }

                                        LocalDateTime now = LocalDateTime.now();
                                        List<Interaction> interactions = coListeners.stream()
                                                .map(userId -> Interaction.builder()
                                                        .userId(userId)
                                                        .trackId(trackId)
                                                        .loungeId(loungeId)
                                                        .playedSeconds(effectiveSeconds)
                                                        .completed(isCompleted)
                                                        .liked(false)
                                                        .createdAt(now)
                                                        .build())
                                                .toList();

                                        return interactionRepository.saveAll(interactions)
                                                .map(saved -> InteractionResponse.fromEntity(saved, track));
                                    });
                        })
                );
    }

    /**
     * Retrieves recent interactions for a user.
     */
    public Flux<InteractionResponse> getUserInteractions(Long userId) {
        if (userId == null) {
            return Flux.error(new IllegalArgumentException("User ID cannot be null"));
        }

        return interactionRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .flatMap(interaction -> trackRepository.findById(interaction.getTrackId())
                        .map(track -> InteractionResponse.fromEntity(interaction, track))
                        .defaultIfEmpty(InteractionResponse.fromEntity(interaction, null))
                );
    }

    /**
     * Retrieves recent interactions for a lounge (for co-listening analysis).
     */
    public Flux<InteractionResponse> getLoungeInteractions(Long loungeId) {
        if (loungeId == null) {
            return Flux.error(new IllegalArgumentException("Lounge ID cannot be null"));
        }

        return interactionRepository.findByLoungeIdOrderByCreatedAtDesc(loungeId)
                .flatMap(interaction -> trackRepository.findById(interaction.getTrackId())
                        .map(track -> InteractionResponse.fromEntity(interaction, track))
                        .defaultIfEmpty(InteractionResponse.fromEntity(interaction, null))
                );
    }

    private Mono<InteractionResponse> processInteraction(Track track, RecordInteractionRequest request, Long userId) {
        int duration = track.getDurationSeconds() != null ? track.getDurationSeconds() : 180;
        int rawSeconds = request.getPlayedSeconds() != null ? request.getPlayedSeconds() : 0;
        boolean liked = Boolean.TRUE.equals(request.getLiked());

        // Sensible boundary 1: Discard sub-5-second tick noise if not liked and not marked completed
        if (rawSeconds < MIN_MEANINGFUL_PLAYED_SECONDS && !liked && !Boolean.TRUE.equals(request.getCompleted())) {
            log.debug("Ignoring sub-5s tick interaction for user {} on track {}", userId, track.getId());
            return Mono.just(InteractionResponse.builder()
                    .userId(userId)
                    .trackId(track.getId())
                    .trackTitle(track.getTitle())
                    .trackArtist(track.getArtist())
                    .playedSeconds(rawSeconds)
                    .completed(false)
                    .liked(false)
                    .createdAt(LocalDateTime.now())
                    .build());
        }

        // Sensible boundary 2: Cap playedSeconds at the actual track duration
        int cappedSeconds = Math.min(rawSeconds, duration);

        // Sensible boundary 3: Determine completion
        // Auto-complete if played >= 85% of track duration
        // Anti-fabricate: Client cannot claim completed if played < 15 seconds or < 85% of duration
        boolean completed = false;
        if (cappedSeconds >= (int) (duration * COMPLETION_PERCENTAGE_THRESHOLD)) {
            completed = true;
        } else if (Boolean.TRUE.equals(request.getCompleted()) && cappedSeconds >= MIN_COMPLETION_SECONDS) {
            completed = true;
        }

        final boolean finalCompleted = completed;
        final int finalCappedSeconds = cappedSeconds;

        // Sensible boundary 4: Debounce rapid repeated writes for same user and track
        return interactionRepository.findTopByUserIdAndTrackIdOrderByCreatedAtDesc(userId, track.getId())
                .flatMap(recent -> {
                    LocalDateTime threshold = LocalDateTime.now().minusSeconds(TICK_DEBOUNCE_SECONDS);
                    if (recent.getCreatedAt() != null && recent.getCreatedAt().isAfter(threshold)) {
                        // Consolidate progress into recent record rather than inserting spam row
                        recent.setPlayedSeconds(Math.max(recent.getPlayedSeconds(), finalCappedSeconds));
                        recent.setCompleted(recent.getCompleted() || finalCompleted);
                        if (request.getLiked() != null) {
                            recent.setLiked(request.getLiked());
                        }
                        if (request.getLoungeId() != null) {
                            recent.setLoungeId(request.getLoungeId());
                        }
                        return interactionRepository.save(recent)
                                .map(saved -> InteractionResponse.fromEntity(saved, track));
                    }

                    return createNewInteraction(track, userId, request.getLoungeId(), finalCappedSeconds, finalCompleted, liked);
                })
                .switchIfEmpty(Mono.defer(() ->
                        createNewInteraction(track, userId, request.getLoungeId(), finalCappedSeconds, finalCompleted, liked)
                ));
    }

    private Mono<InteractionResponse> createNewInteraction(
            Track track,
            Long userId,
            Long loungeId,
            int playedSeconds,
            boolean completed,
            boolean liked
    ) {
        Interaction interaction = Interaction.builder()
                .userId(userId)
                .trackId(track.getId())
                .loungeId(loungeId)
                .playedSeconds(playedSeconds)
                .completed(completed)
                .liked(liked)
                .createdAt(LocalDateTime.now())
                .build();

        return interactionRepository.save(interaction)
                .map(saved -> InteractionResponse.fromEntity(saved, track));
    }

    private Mono<Void> validateLoungeIfPresent(Long loungeId) {
        if (loungeId == null) {
            return Mono.empty();
        }
        return djLoungeRepository.findById(loungeId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Lounge not found with id: " + loungeId)))
                .then();
    }
}
