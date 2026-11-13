package com.aidjstudio.ai_dj_studio.websocket;

import com.aidjstudio.ai_dj_studio.dto.websocket.LoungeSnapshotPayload;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsConnectionPayload;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsErrorPayload;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsPingPongPayload;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsPresencePayload;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.repository.QueueEntryRepository;
import com.aidjstudio.ai_dj_studio.service.QueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoungeWebSocketHandler implements WebSocketHandler {

    private static final Pattern LOUNGE_PATH_PATTERN = Pattern.compile("^/ws/lounges/(\\d+)/?$");

    private final ReactiveJwtDecoder reactiveJwtDecoder;
    private final DjLoungeRepository djLoungeRepository;
    private final LoungeParticipantRepository loungeParticipantRepository;
    private final QueueEntryRepository queueEntryRepository;
    private final QueueService queueService;
    private final LoungeSessionManager loungeSessionManager;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        HandshakeInfo handshakeInfo = session.getHandshakeInfo();
        URI uri = handshakeInfo.getUri();
        String path = uri != null ? uri.getPath() : "";

        Matcher matcher = LOUNGE_PATH_PATTERN.matcher(path);
        if (!matcher.matches()) {
            return sendErrorAndClose(session, null, "INVALID_PATH", "Invalid lounge WebSocket path");
        }

        Long loungeId;
        try {
            loungeId = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return sendErrorAndClose(session, null, "INVALID_LOUNGE_ID", "Invalid lounge ID format");
        }

        String token = extractToken(handshakeInfo);
        if (token == null || token.isBlank()) {
            return sendErrorAndClose(session, loungeId, "MISSING_TOKEN", "Authentication token is missing");
        }

        return reactiveJwtDecoder.decode(token)
                .flatMap(jwt -> {
                    Long userId;
                    try {
                        userId = Long.valueOf(jwt.getSubject());
                    } catch (NumberFormatException e) {
                        return sendErrorAndClose(session, loungeId, "INVALID_TOKEN", "Invalid token subject");
                    }
                    return validateAndConnect(session, loungeId, userId);
                })
                .onErrorResume(ex -> {
                    log.debug("JWT decoding failed for WebSocket handshake: {}", ex.getMessage());
                    return sendErrorAndClose(session, loungeId, "INVALID_TOKEN", "Invalid or expired authentication token");
                });
    }

    private Mono<Void> validateAndConnect(WebSocketSession session, Long loungeId, Long userId) {
        return djLoungeRepository.findById(loungeId)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(optLounge -> {
                    if (optLounge.isEmpty()) {
                        return sendErrorAndClose(session, loungeId, "LOUNGE_NOT_FOUND", "Lounge not found with id: " + loungeId);
                    }

                    DjLounge lounge = optLounge.get();
                    if (lounge.getStatus() == LoungeStatus.ENDED) {
                        return sendErrorAndClose(session, loungeId, "LOUNGE_ENDED", "Cannot connect to an ended lounge");
                    }

                    loungeSessionManager.recordHostUser(loungeId, lounge.getHostUserId());

                    if (lounge.getHostUserId().equals(userId)) {
                        return establishConnection(session, lounge, userId, "HOST");
                    }

                    return loungeParticipantRepository
                            .findByLoungeIdAndUserIdAndLeftAtIsNull(loungeId, userId)
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty())
                            .flatMap(optParticipant -> {
                                if (optParticipant.isEmpty()) {
                                    return sendErrorAndClose(session, loungeId, "NOT_A_MEMBER", "User is not an active participant in this lounge");
                                }

                                LoungeParticipant participant = optParticipant.get();
                                String role = participant.getRole() != null ? participant.getRole().name() : "LISTENER";
                                return establishConnection(session, lounge, userId, role);
                            });
                });
    }

    private Mono<Void> establishConnection(WebSocketSession session, DjLounge lounge, Long userId, String role) {
        Long loungeId = lounge.getId();
        boolean isReconnect = loungeSessionManager.isUserConnected(loungeId, userId);
        boolean isHost = "HOST".equalsIgnoreCase(role);
        boolean wasHostDisconnected = isHost && loungeSessionManager.isQueueFrozen(loungeId);

        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        loungeSessionManager.registerSession(loungeId, session, userId, role, sink);

        if (isHost) {
            loungeSessionManager.setQueueFrozen(loungeId, false);
        }

        return buildSnapshotPayload(loungeId, lounge)
                .flatMap(snapshot -> {
                    WsEventEnvelope<WsConnectionPayload> ackEnvelope = WsEventEnvelope.<WsConnectionPayload>builder()
                            .event("connection_established")
                            .loungeId(loungeId)
                            .payload(WsConnectionPayload.builder()
                                    .userId(userId)
                                    .role(role)
                                    .message(isReconnect ? "Reconnected to lounge " + loungeId : "Connected to lounge " + loungeId)
                                    .build())
                            .ts(Instant.now())
                            .build();

                    WsEventEnvelope<LoungeSnapshotPayload> snapshotEnvelope = WsEventEnvelope.<LoungeSnapshotPayload>builder()
                            .event("lounge.snapshot")
                            .loungeId(loungeId)
                            .payload(snapshot)
                            .ts(Instant.now())
                            .build();

                    String ackJson;
                    String snapshotJson;
                    try {
                        ackJson = objectMapper.writeValueAsString(ackEnvelope);
                        snapshotJson = objectMapper.writeValueAsString(snapshotEnvelope);
                    } catch (Exception e) {
                        log.error("Failed to serialize initial connection events", e);
                        loungeSessionManager.removeSession(loungeId, session);
                        return session.close(CloseStatus.SERVER_ERROR);
                    }

                    if (wasHostDisconnected) {
                        loungeSessionManager.broadcast(loungeId,
                                buildPresenceEnvelope(loungeId, userId, role, "host_reconnected", "HOST_RECONNECTED"));
                    } else if (!isReconnect && !isHost) {
                        loungeSessionManager.broadcastExcept(loungeId,
                                buildPresenceEnvelope(loungeId, userId, role, "joined", "lounge.presence.update"),
                                session.getId());
                    }

                    Flux<WebSocketMessage> initialMessages = Flux.just(
                            session.textMessage(ackJson),
                            session.textMessage(snapshotJson)
                    );

                    Flux<WebSocketMessage> outboundMessages = Flux.concat(
                            initialMessages,
                            sink.asFlux().map(session::textMessage)
                    );

                    Mono<Void> sendMono = session.send(outboundMessages);

                    Mono<Void> receiveMono = session.receive()
                            .flatMap(msg -> handleInboundMessage(session, loungeId, userId, role, msg.getPayloadAsText()))
                            .then();

                    return Mono.when(sendMono, receiveMono)
                            .doFinally(signalType -> {
                                log.info("WebSocket session closed for user {} in lounge {} (signal: {})", userId, loungeId, signalType);
                                loungeSessionManager.removeSession(loungeId, session);
                                boolean stillConnected = loungeSessionManager.isUserConnected(loungeId, userId);
                                if (!stillConnected) {
                                    if (isHost) {
                                        loungeSessionManager.setQueueFrozen(loungeId, true);
                                        loungeSessionManager.broadcast(loungeId,
                                                buildPresenceEnvelope(loungeId, userId, role, "host_disconnected", "HOST_DISCONNECTED"));
                                    } else {
                                        loungeSessionManager.broadcast(loungeId,
                                                buildPresenceEnvelope(loungeId, userId, role, "left", "lounge.presence.update"));
                                    }
                                }
                            });
                });
    }

    private Mono<Void> handleInboundMessage(WebSocketSession session, Long loungeId, Long userId, String role, String payloadText) {
        if (payloadText == null || payloadText.isBlank()) {
            return Mono.empty();
        }

        try {
            Map<?, ?> map = objectMapper.readValue(payloadText, Map.class);
            Object eventObj = map.get("event");
            String event = eventObj != null ? eventObj.toString() : "";

            if ("lounge.sync.ping".equalsIgnoreCase(event) || "ping".equalsIgnoreCase(event)) {
                Object payloadObj = map.get("payload");
                long clientSentAt = Instant.now().toEpochMilli();
                if (payloadObj instanceof Map<?, ?> payloadMap) {
                    Object sentAt = payloadMap.get("clientSentAt");
                    if (sentAt instanceof Number num) {
                        clientSentAt = num.longValue();
                    }
                }
                WsEventEnvelope<WsPingPongPayload> pong = WsEventEnvelope.<WsPingPongPayload>builder()
                        .event("lounge.sync.pong")
                        .loungeId(loungeId)
                        .payload(WsPingPongPayload.builder()
                                .clientSentAt(clientSentAt)
                                .serverReceivedAt(Instant.now().toEpochMilli())
                                .build())
                        .ts(Instant.now())
                        .build();

                loungeSessionManager.sendToSession(session, pong);
                return Mono.empty();
            }

            if ("resync".equalsIgnoreCase(event) || "lounge.sync.resync".equalsIgnoreCase(event) || "get_snapshot".equalsIgnoreCase(event)) {
                return sendSnapshotToSession(session, loungeId);
            }
        } catch (Exception e) {
            log.debug("Unrecognized inbound WebSocket message payload: {}", payloadText);
        }

        return Mono.empty();
    }

    private Mono<Void> sendSnapshotToSession(WebSocketSession session, Long loungeId) {
        return djLoungeRepository.findById(loungeId)
                .flatMap(lounge -> buildSnapshotPayload(loungeId, lounge))
                .map(snapshot -> WsEventEnvelope.<LoungeSnapshotPayload>builder()
                        .event("lounge.snapshot")
                        .loungeId(loungeId)
                        .payload(snapshot)
                        .ts(Instant.now())
                        .build())
                .doOnNext(envelope -> loungeSessionManager.sendToSession(session, envelope))
                .then();
    }

    private Mono<LoungeSnapshotPayload> buildSnapshotPayload(Long loungeId, DjLounge lounge) {
        return queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId)
                .map(queueService::toResponseDto)
                .collectList()
                .map(nextUp -> LoungeSnapshotPayload.builder()
                        .loungeId(loungeId)
                        .status(lounge.getStatus())
                        .hostUserId(lounge.getHostUserId())
                        .hostConnected(loungeSessionManager.isHostConnected(loungeId))
                        .queueFrozen(loungeSessionManager.isQueueFrozen(loungeId))
                        .currentTrackId(lounge.getCurrentTrackId())
                        .nextUp(nextUp)
                        .activeSoundclash(null)
                        .participantCount(loungeSessionManager.getActiveCount(loungeId))
                        .build());
    }

    private WsEventEnvelope<WsPresencePayload> buildPresenceEnvelope(Long loungeId, Long userId, String role, String action, String eventName) {
        return WsEventEnvelope.<WsPresencePayload>builder()
                .event(eventName)
                .loungeId(loungeId)
                .payload(WsPresencePayload.builder()
                        .userId(userId)
                        .role(role)
                        .action(action)
                        .participantCount(loungeSessionManager.getActiveCount(loungeId))
                        .build())
                .ts(Instant.now())
                .build();
    }

    private Mono<Void> sendErrorAndClose(WebSocketSession session, Long loungeId, String code, String message) {
        WsEventEnvelope<WsErrorPayload> errorEnvelope = WsEventEnvelope.<WsErrorPayload>builder()
                .event("error")
                .loungeId(loungeId)
                .payload(WsErrorPayload.builder()
                        .code(code)
                        .message(message)
                        .build())
                .ts(Instant.now())
                .build();

        String json;
        try {
            json = objectMapper.writeValueAsString(errorEnvelope);
        } catch (Exception e) {
            return session.close(CloseStatus.POLICY_VIOLATION);
        }

        return session.send(Mono.just(session.textMessage(json)))
                .then(session.close(CloseStatus.POLICY_VIOLATION));
    }

    private String extractToken(HandshakeInfo handshakeInfo) {
        // 1. Try Authorization header: Bearer <token>
        String authHeader = handshakeInfo.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authHeader.substring(7).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }

        // 2. Try Query parameter: ?token=<token>
        URI uri = handshakeInfo.getUri();
        if (uri != null && uri.getQuery() != null) {
            String[] queryParams = uri.getQuery().split("&");
            for (String param : queryParams) {
                int eqIdx = param.indexOf('=');
                if (eqIdx > 0) {
                    String name = URLDecoder.decode(param.substring(0, eqIdx), StandardCharsets.UTF_8);
                    if ("token".equalsIgnoreCase(name)) {
                        return URLDecoder.decode(param.substring(eqIdx + 1), StandardCharsets.UTF_8);
                    }
                }
            }
        }

        return null;
    }
}
