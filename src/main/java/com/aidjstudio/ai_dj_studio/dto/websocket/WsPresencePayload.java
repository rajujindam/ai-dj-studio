package com.aidjstudio.ai_dj_studio.dto.websocket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WsPresencePayload {
    private Long userId;
    private String role;
    private String action;
    private int participantCount;
}
