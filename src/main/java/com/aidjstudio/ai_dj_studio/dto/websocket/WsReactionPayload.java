package com.aidjstudio.ai_dj_studio.dto.websocket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WsReactionPayload {

    private Long userId;
    private String emoji;
    private String targetType; // Optional target: "LOUNGE", "TRACK", "CHAT"
    private Long targetId;     // Optional ID of track or chat message
}
