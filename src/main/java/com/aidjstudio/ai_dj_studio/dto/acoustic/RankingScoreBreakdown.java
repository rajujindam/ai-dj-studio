package com.aidjstudio.ai_dj_studio.dto.acoustic;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RankingScoreBreakdown {

    private double totalScore;

    // Harmonic Closeness (50% weight)
    private double harmonicScore;
    private double harmonicWeight;
    private double harmonicWeightedScore;

    // Energy Curve Fit (30% weight)
    private double energyScore;
    private double energyWeight;
    private double energyWeightedScore;
    private BigDecimal candidateEnergy;
    private BigDecimal targetEnergy;
    private BigDecimal baseEnergy;
    private Double sentimentScore;
    private BigDecimal sentimentEnergyShift;

    // Recency (20% weight)
    private double recencyScore;
    private double recencyWeight;
    private double recencyWeightedScore;
    private Integer recentIndex; // 0-based index in recent list, or null if not recent

    private String reason;
}
