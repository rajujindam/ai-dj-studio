package com.aidjstudio.ai_dj_studio.dto.sentiment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageSentiment {

    private String messageId;
    private Long userId;
    private String text;
    private double score; // -1.0 to +1.0
    private SentimentBucket bucket;
    private List<String> positiveTokens;
    private List<String> negativeTokens;
    private Instant timestamp;
}
