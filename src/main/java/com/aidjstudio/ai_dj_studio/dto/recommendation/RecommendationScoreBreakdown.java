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
public class RecommendationScoreBreakdown {

    private double totalScore;

    // BPM component (30%)
    private double bpmScore;
    private double bpmWeight;
    private double bpmWeightedScore;
    private BigDecimal bpmDelta;

    // Key component (30%)
    private double keyScore;
    private double keyWeight;
    private double keyWeightedScore;
    private String keyRelation;

    // Energy component (20%)
    private double energyScore;
    private double energyWeight;
    private double energyWeightedScore;
    private BigDecimal energyDelta;

    // Valence component (20%)
    private double valenceScore;
    private double valenceWeight;
    private double valenceWeightedScore;
    private BigDecimal valenceDelta;

    private String reason;
}
