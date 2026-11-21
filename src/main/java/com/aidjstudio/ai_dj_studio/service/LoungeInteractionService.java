package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsReactionPayload;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsTypingPayload;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.websocket.LoungeSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for ephemeral, transient interactions in a lounge:
 * 1. Typing indicators (who is currently typing in the chat)
 * 2. Emoji reactions (live crowd emoji reactions)
 *
 * Characteristics:
 * - Completely transient (in-memory; zero database persistence).
 * - Server-derived identity (userId cannot be spoofed).
 * - Enforces allowed emoji whitelist.
 * - Enforces rate-limiting / deduplication per user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoungeInteractionService {

    public static final Set<String> ALLOWED_EMOJIS = Set.of(
            "🔥", "❤️", "👏", "🎉", "🙌", "💃", "🕺", "✨", "🚀", "💯", "👎", "💀"
    );

    // Minimum interval in milliseconds between reactions per user to prevent spam
    public static final long REACTION_THROTTLE_MS = 1000L;

    private final DjLoungeRepository djLoungeRepository;
    private final LoungeParticipantRepository loungeParticipantRepository;
    private final LoungeSessionManager loungeSessionManager;

    // Track last reaction time per user in a lounge: "loungeId:userId" -> timestampMs
    private final Map<String, Long> lastReactionTimes = new ConcurrentHashMap<>();

    /**
     * Broadcasts typing status for an authenticated participant.
     */
    public Mono<Void> sendTypingIndicator(Long loungeId, Long authenticatedUserId, boolean typing) {
        if (loungeId == null) {
            return Mono.error(new IllegalArgumentException("Lounge ID cannot be null"));
        }
        if (authenticatedUserId == null) {
            return Mono.error(new AccessDeniedException("User must be authenticated"));
        }

        return verifyLoungeAndMembership(loungeId, authenticatedUserId)
                .doOnNext(lounge -> {
                    WsTypingPayload payload = WsTypingPayload.builder()
                            .userId(authenticatedUserId)
                            .typing(typing)
                            .build();

                    WsEventEnvelope<WsTypingPayload> envelope = WsEventEnvelope.<WsTypingPayload>builder()
                            .event("lounge.typing")
                            .loungeId(loungeId)
                            .payload(payload)
                            .ts(Instant.now())
                            .build();

                    // Broadcast to lounge peers
                    loungeSessionManager.broadcast(loungeId, envelope);
                })
                .then();
    }

    /**
     * Broadcasts an emoji reaction after validating allowed emojis and throttling spam.
     */
    public Mono<Void> sendReaction(Long loungeId, Long authenticatedUserId, String emoji, String targetType, Long targetId) {
        if (loungeId == null) {
            return Mono.error(new IllegalArgumentException("Lounge ID cannot be null"));
        }
        if (authenticatedUserId == null) {
            return Mono.error(new AccessDeniedException("User must be authenticated"));
        }
        if (emoji == null || emoji.isBlank()) {
            return Mono.error(new IllegalArgumentException("Emoji cannot be blank"));
        }

        String trimmedEmoji = emoji.trim();
        if (!ALLOWED_EMOJIS.contains(trimmedEmoji)) {
            return Mono.error(new IllegalArgumentException("Emoji is not in the allowed reaction set: " + trimmedEmoji));
        }

        return verifyLoungeAndMembership(loungeId, authenticatedUserId)
                .flatMap(lounge -> {
                    String throttleKey = loungeId + ":" + authenticatedUserId;
                    long now = System.currentTimeMillis();
                    Long lastTime = lastReactionTimes.get(throttleKey);

                    if (lastTime != null && (now - lastTime) < REACTION_THROTTLE_MS) {
                        log.debug("Throttling repeated reaction from user {} in lounge {}", authenticatedUserId, loungeId);
                        return Mono.empty(); // Drop duplicate/spam without error
                    }

                    lastReactionTimes.put(throttleKey, now);

                    WsReactionPayload payload = WsReactionPayload.builder()
                            .userId(authenticatedUserId)
                            .emoji(trimmedEmoji)
                            .targetType(targetType != null && !targetType.isBlank() ? targetType.trim().toUpperCase() : "LOUNGE")
                            .targetId(targetId)
                            .build();

                    WsEventEnvelope<WsReactionPayload> envelope = WsEventEnvelope.<WsReactionPayload>builder()
                            .event("lounge.reaction")
                            .loungeId(loungeId)
                            .payload(payload)
                            .ts(Instant.now())
                            .build();

                    loungeSessionManager.broadcast(loungeId, envelope);
                    return Mono.empty();
                });
    }

    private Mono<DjLounge> verifyLoungeAndMembership(Long loungeId, Long authenticatedUserId) {
        return djLoungeRepository.findById(loungeId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Lounge not found with id: " + loungeId)))
                .flatMap(lounge -> {
                    if (lounge.getStatus() == LoungeStatus.ENDED) {
                        return Mono.error(new IllegalStateException("Cannot interact in an ended lounge"));
                    }

                    // Host is always authorized
                    if (lounge.getHostUserId().equals(authenticatedUserId)) {
                        return Mono.just(lounge);
                    }

                    // Non-host listeners must be active participants (left_at IS NULL)
                    return loungeParticipantRepository
                            .findByLoungeIdAndUserIdAndLeftAtIsNull(loungeId, authenticatedUserId)
                            .switchIfEmpty(Mono.error(new AccessDeniedException("User is not an active participant in this lounge")))
                            .thenReturn(lounge);
                });
    }

    public void clearThrottling(Long loungeId, Long userId) {
        if (loungeId != null && userId != null) {
            lastReactionTimes.remove(loungeId + ":" + userId);
        }
    }
}
