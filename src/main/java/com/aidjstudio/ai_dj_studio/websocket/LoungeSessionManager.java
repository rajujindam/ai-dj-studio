package com.aidjstudio.ai_dj_studio.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoungeSessionManager {

    private final Map<Long, Set<WebSocketSession>> loungeSessions = new ConcurrentHashMap<>();

    public void addSession(Long loungeId, WebSocketSession session) {
        loungeSessions.computeIfAbsent(loungeId, key -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void removeSession(Long loungeId, WebSocketSession session) {
        Set<WebSocketSession> sessions = loungeSessions.get(loungeId);
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                loungeSessions.remove(loungeId);
            }
        }
    }

    public Set<WebSocketSession> getSessions(Long loungeId) {
        Set<WebSocketSession> sessions = loungeSessions.get(loungeId);
        return sessions != null ? Collections.unmodifiableSet(sessions) : Collections.emptySet();
    }

    public int getActiveCount(Long loungeId) {
        Set<WebSocketSession> sessions = loungeSessions.get(loungeId);
        return sessions != null ? sessions.size() : 0;
    }
}
