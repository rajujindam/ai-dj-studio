package com.aidjstudio.ai_dj_studio.dto.websocket;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WsEventEnvelope<T> {
    private String event;
    private Long loungeId;
    private T payload;
    private Instant ts;
}
