package com.aidjstudio.ai_dj_studio.dto.sentiment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomSentimentSnapshot {

    private Long loungeId;
    private double roomScore; // -1.000 to +1.000
    private double normalizedVibe; // 0.000 to 1.000 (0.50 = neutral)
    private SentimentBucket dominantBucket;

    private int totalSamples;
    private int positiveCount;
    private int neutralCount;
    private int negativeCount;

    private int windowSeconds;
    private Instant windowStartTime;
    private Instant timestamp;

    private String status; // "EMPTY", "LOW_SAMPLE", "ACTIVE"
    private String explanation;
}
