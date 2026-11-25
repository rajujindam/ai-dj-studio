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
public class UserTasteProfile {

    private Long userId;
    private BigDecimal averageBpm;
    private String dominantCamelotKey;
    private BigDecimal averageEnergy;
    private BigDecimal averageValence;
    private int positiveInteractionCount;
    private boolean coldStart;
}
