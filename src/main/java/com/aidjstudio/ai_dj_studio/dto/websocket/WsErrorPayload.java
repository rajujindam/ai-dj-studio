package com.aidjstudio.ai_dj_studio.dto.websocket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WsErrorPayload {
    private String code;
    private String message;
}
