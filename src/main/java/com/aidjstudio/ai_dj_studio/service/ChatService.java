package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.chat.ChatMessageResponse;
import com.aidjstudio.ai_dj_studio.dto.chat.SendChatMessageRequest;
import com.aidjstudio.ai_dj_studio.dto.sentiment.MessageSentiment;
import com.aidjstudio.ai_dj_studio.dto.sentiment.RoomSentimentSnapshot;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import com.aidjstudio.ai_dj_studio.entity.ChatMessage;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.repository.ChatMessageRepository;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.service.sentiment.RollingWindowSentimentService;
import com.aidjstudio.ai_dj_studio.websocket.LoungeSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    public static final int MAX_MESSAGE_LENGTH = 500;
    public static final int DEFAULT_HISTORY_LIMIT = 50;
    public static final int MAX_HISTORY_LIMIT = 100;

    private final ChatMessageRepository chatMessageRepository;
    private final DjLoungeRepository djLoungeRepository;
    private final LoungeParticipantRepository loungeParticipantRepository;
    private final RollingWindowSentimentService rollingWindowSentimentService;
    private final LoungeSessionManager loungeSessionManager;

    /**
     * Sends, persists, scores, and broadcasts a chat message.
     */
    public Mono<ChatMessageResponse> sendMessage(Long loungeId, SendChatMessageRequest request, Long authenticatedUserId) {
        if (loungeId == null) {
            return Mono.error(new IllegalArgumentException("Lounge ID cannot be null"));
        }
        if (request == null || request.getBody() == null || request.getBody().isBlank()) {
            return Mono.error(new IllegalArgumentException("Message body cannot be blank"));
        }
        if (request.getBody().length() > MAX_MESSAGE_LENGTH) {
            return Mono.error(new IllegalArgumentException("Message body exceeds maximum length of " + MAX_MESSAGE_LENGTH + " characters"));
        }

        return verifyLoungeAndMembership(loungeId, authenticatedUserId)
                .flatMap(lounge -> {
                    // Sanitize message body against XSS attacks before storing/broadcasting
                    String sanitizedBody = HtmlUtils.htmlEscape(request.getBody().trim());
                    String msgType = (request.getMsgType() != null && !request.getMsgType().isBlank())
                            ? request.getMsgType().trim().toUpperCase()
                            : "TEXT";

                    // Score sentiment using the centralized sentiment engine
                    Instant now = Instant.now();
                    MessageSentiment messageSentiment = rollingWindowSentimentService.recordMessage(
                            loungeId, sanitizedBody, authenticatedUserId, now
                    );
                    BigDecimal sentimentScore = BigDecimal.valueOf(messageSentiment.getScore())
                            .setScale(3, RoundingMode.HALF_UP);

                    ChatMessage chatMessage = ChatMessage.builder()
                            .loungeId(loungeId)
                            .userId(authenticatedUserId) // Server-derived, client cannot spoof
                            .msgType(msgType)
                            .body(sanitizedBody)
                            .sentimentScore(sentimentScore)
                            .tsSeconds((int) (now.toEpochMilli() / 1000L))
                            .createdAt(LocalDateTime.now())
                            .build();

                    return chatMessageRepository.save(chatMessage)
                            .map(this::toResponseDto)
                            .doOnNext(responseDto -> {
                                broadcastChatMessage(loungeId, responseDto);
                                broadcastSentimentUpdate(loungeId);
                            });
                });
    }

    /**
     * Retrieves recent chat message history for a lounge (ordered newest to oldest).
     */
    public Flux<ChatMessageResponse> getChatHistory(Long loungeId, int limit, Long authenticatedUserId) {
        if (loungeId == null) {
            return Flux.error(new IllegalArgumentException("Lounge ID cannot be null"));
        }
        int effectiveLimit = limit <= 0 ? DEFAULT_HISTORY_LIMIT : Math.min(limit, MAX_HISTORY_LIMIT);

        return verifyLoungeAndMembership(loungeId, authenticatedUserId)
                .flatMapMany(lounge -> chatMessageRepository.findRecentByLoungeId(loungeId, effectiveLimit))
                .map(this::toResponseDto);
    }

    private Mono<DjLounge> verifyLoungeAndMembership(Long loungeId, Long authenticatedUserId) {
        if (authenticatedUserId == null) {
            return Mono.error(new AccessDeniedException("Authentication required"));
        }

        return djLoungeRepository.findById(loungeId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Lounge not found with id: " + loungeId)))
                .flatMap(lounge -> {
                    if (lounge.getStatus() == LoungeStatus.ENDED) {
                        return Mono.error(new IllegalStateException("Cannot access chat of an ended lounge"));
                    }

                    // Host is always authorized
                    if (lounge.getHostUserId().equals(authenticatedUserId)) {
                        return Mono.just(lounge);
                    }

                    // Listeners/participants must have an active membership (left_at IS NULL)
                    return loungeParticipantRepository
                            .findByLoungeIdAndUserIdAndLeftAtIsNull(loungeId, authenticatedUserId)
                            .switchIfEmpty(Mono.error(new AccessDeniedException("User is not an active participant in this lounge")))
                            .thenReturn(lounge);
                });
    }

    private void broadcastChatMessage(Long loungeId, ChatMessageResponse responseDto) {
        WsEventEnvelope<ChatMessageResponse> envelope = WsEventEnvelope.<ChatMessageResponse>builder()
                .event("chat.message")
                .loungeId(loungeId)
                .payload(responseDto)
                .ts(Instant.now())
                .build();

        loungeSessionManager.broadcast(loungeId, envelope);
    }

    private void broadcastSentimentUpdate(Long loungeId) {
        RoomSentimentSnapshot snapshot = rollingWindowSentimentService.getRoomSentiment(loungeId);
        WsEventEnvelope<RoomSentimentSnapshot> envelope = WsEventEnvelope.<RoomSentimentSnapshot>builder()
                .event("sentiment.update")
                .loungeId(loungeId)
                .payload(snapshot)
                .ts(Instant.now())
                .build();

        loungeSessionManager.broadcast(loungeId, envelope);
    }

    public ChatMessageResponse toResponseDto(ChatMessage entity) {
        return ChatMessageResponse.builder()
                .id(entity.getId())
                .loungeId(entity.getLoungeId())
                .userId(entity.getUserId())
                .msgType(entity.getMsgType())
                .body(entity.getBody())
                .sentimentScore(entity.getSentimentScore())
                .tsSeconds(entity.getTsSeconds())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
