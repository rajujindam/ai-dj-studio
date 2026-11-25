package com.aidjstudio.ai_dj_studio.dto.recommendation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendedTrackResponse {

    private int rank;
    private Long trackId;
    private String title;
    private String artist;
    private Integer durationSeconds;
    private BigDecimal bpm;
    private String camelotKey;
    private BigDecimal energy;
    private BigDecimal valence;
    private double similarityScore;
    private String reason;
    private RecommendationScoreBreakdown scoreBreakdown;
}
