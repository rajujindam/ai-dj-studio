package com.aidjstudio.ai_dj_studio.dto.transition;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransitionDecisionRequest {

    @NotEmpty(message = "Candidate track IDs list cannot be empty")
    private List<Long> candidateTrackIds;

    @DecimalMin(value = "0.0", message = "Target energy must be between 0.0 and 1.0")
    @DecimalMax(value = "1.0", message = "Target energy must be between 0.0 and 1.0")
    private BigDecimal targetEnergy;

    private List<Long> recentTrackIds;

    private Double bpmTolerancePct;
}
