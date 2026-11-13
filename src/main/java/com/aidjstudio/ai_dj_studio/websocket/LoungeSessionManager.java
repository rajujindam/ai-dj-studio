package com.aidjstudio.ai_dj_studio.websocket;

import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class LoungeSessionManager {

    @Getter
    public static class SessionContext {
        private final Long loungeId;
        private final Long userId;
        private final String role;
        private final WebSocketSession session;
        private final Sinks.Many<String> sink;

        public SessionContext(Long loungeId, Long userId, String role, WebSocketSession session, Sinks.Many<String> sink) {
            this.loungeId = loungeId;
            this.userId = userId;
            this.role = role;
            this.session = session;
            this.sink = sink;
        }

        public void send(String json) {
            if (sink != null) {
                sink.tryEmitNext(json);
            }
        }
    }

    private final ObjectMapper objectMapper;
    private final Map<Long, Set<WebSocketSession>> loungeSessions = new ConcurrentHashMap<>();
    private final Map<Long, Map<Long, Set<String>>> loungeUserSessions = new ConcurrentHashMap<>();
    private final Map<String, SessionContext> sessionContexts = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> queueFrozenByLounge = new ConcurrentHashMap<>();
    private final Map<Long, Long> hostUserByLounge = new ConcurrentHashMap<>();

    public LoungeSessionManager() {
        this.objectMapper = new ObjectMapper();
    }

    public LoungeSessionManager(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public void addSession(Long loungeId, WebSocketSession session) {
        loungeSessions.computeIfAbsent(loungeId, key -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void registerSession(Long loungeId, WebSocketSession session, Long userId, String role, Sinks.Many<String> sink) {
        addSession(loungeId, session);

        if ("HOST".equalsIgnoreCase(role)) {
            hostUserByLounge.put(loungeId, userId);
        }

        loungeUserSessions.computeIfAbsent(loungeId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                .add(session.getId());

        sessionContexts.put(session.getId(), new SessionContext(loungeId, userId, role, session, sink));
    }

    public void removeSession(Long loungeId, WebSocketSession session) {
        Set<WebSocketSession> sessions = loungeSessions.get(loungeId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                loungeSessions.remove(loungeId);
            }
        }

        SessionContext context = sessionContexts.remove(session.getId());
        if (context != null) {
            if (context.getSink() != null) {
                context.getSink().tryEmitComplete();
            }

            Map<Long, Set<String>> userMap = loungeUserSessions.get(loungeId);
            if (userMap != null) {
                Set<String> userSessionIds = userMap.get(context.getUserId());
                if (userSessionIds != null) {
                    userSessionIds.remove(session.getId());
                    if (userSessionIds.isEmpty()) {
                        userMap.remove(context.getUserId());
                    }
                }
                if (userMap.isEmpty()) {
                    loungeUserSessions.remove(loungeId);
                }
            }
        }
    }

    public boolean isUserConnected(Long loungeId, Long userId) {
        Map<Long, Set<String>> userMap = loungeUserSessions.get(loungeId);
        if (userMap != null) {
            Set<String> sessions = userMap.get(userId);
            return sessions != null && !sessions.isEmpty();
        }
        return false;
    }

    public boolean isHostConnected(Long loungeId) {
        Long hostUserId = hostUserByLounge.get(loungeId);
        if (hostUserId != null) {
            return isUserConnected(loungeId, hostUserId);
        }
        return sessionContexts.values().stream()
                .anyMatch(ctx -> ctx.getLoungeId().equals(loungeId) && "HOST".equalsIgnoreCase(ctx.getRole()));
    }

    public void recordHostUser(Long loungeId, Long hostUserId) {
        hostUserByLounge.put(loungeId, hostUserId);
    }

    public boolean isQueueFrozen(Long loungeId) {
        return queueFrozenByLounge.getOrDefault(loungeId, false);
    }

    public void setQueueFrozen(Long loungeId, boolean frozen) {
        queueFrozenByLounge.put(loungeId, frozen);
    }

    public Set<WebSocketSession> getSessions(Long loungeId) {
        Set<WebSocketSession> sessions = loungeSessions.get(loungeId);
        return sessions != null ? Collections.unmodifiableSet(sessions) : Collections.emptySet();
    }

    public int getActiveCount(Long loungeId) {
        Map<Long, Set<String>> userMap = loungeUserSessions.get(loungeId);
        if (userMap != null) {
            return userMap.size();
        }
        Set<WebSocketSession> sessions = loungeSessions.get(loungeId);
        return sessions != null ? sessions.size() : 0;
    }

    public void broadcast(Long loungeId, WsEventEnvelope<?> envelope) {
        broadcastExcept(loungeId, envelope, null);
    }

    public void broadcastExcept(Long loungeId, WsEventEnvelope<?> envelope, String excludeSessionId) {
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("Failed to serialize WebSocket event for broadcast", e);
            return;
        }

        for (SessionContext ctx : sessionContexts.values()) {
            if (ctx.getLoungeId().equals(loungeId)) {
                if (excludeSessionId == null || !ctx.getSession().getId().equals(excludeSessionId)) {
                    ctx.send(json);
                }
            }
        }
    }

    public void sendToSession(WebSocketSession session, WsEventEnvelope<?> envelope) {
        SessionContext ctx = sessionContexts.get(session.getId());
        if (ctx != null) {
            try {
                ctx.send(objectMapper.writeValueAsString(envelope));
            } catch (Exception e) {
                log.error("Failed to serialize WebSocket event for session {}", session.getId(), e);
            }
        }
    }
}
