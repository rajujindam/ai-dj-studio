package com.aidjstudio.ai_dj_studio.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageResponse {

    private Long id;
    private Long loungeId;
    private Long userId;
    private String msgType;
    private String body;
    private BigDecimal sentimentScore;
    private Integer tsSeconds;
    private LocalDateTime createdAt;
}
