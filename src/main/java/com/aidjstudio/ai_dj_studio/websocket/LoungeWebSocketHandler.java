package com.aidjstudio.ai_dj_studio.websocket;

import com.aidjstudio.ai_dj_studio.dto.websocket.WsConnectionPayload;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsErrorPayload;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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

                    if (lounge.getHostUserId().equals(userId)) {
                        return establishConnection(session, loungeId, userId, "HOST");
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
                                return establishConnection(session, loungeId, userId, role);
                            });
                });
    }

    private Mono<Void> establishConnection(WebSocketSession session, Long loungeId, Long userId, String role) {
        loungeSessionManager.addSession(loungeId, session);

        WsEventEnvelope<WsConnectionPayload> envelope = WsEventEnvelope.<WsConnectionPayload>builder()
                .event("connection_established")
                .loungeId(loungeId)
                .payload(WsConnectionPayload.builder()
                        .userId(userId)
                        .role(role)
                        .message("Connected to lounge " + loungeId)
                        .build())
                .ts(Instant.now())
                .build();

        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("Failed to serialize connection_established event", e);
            loungeSessionManager.removeSession(loungeId, session);
            return session.close(CloseStatus.SERVER_ERROR);
        }

        return session.send(Mono.just(session.textMessage(json)))
                .thenMany(session.receive())
                .doFinally(signalType -> {
                    log.info("WebSocket session closed for user {} in lounge {} (signal: {})", userId, loungeId, signalType);
                    loungeSessionManager.removeSession(loungeId, session);
                })
                .then();
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
