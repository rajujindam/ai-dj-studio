package com.aidjstudio.ai_dj_studio.dto.lounge;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateLoungeRequest {

    @NotNull(message = "seedTrackId is required")
    private Long seedTrackId;

    private String vibeProfile;

    @Min(value = 2, message = "maxParticipants must be at least 2")
    @Max(value = 1000, message = "maxParticipants must not exceed 1000")
    private Integer maxParticipants;

    @DecimalMin(value = "0.0", message = "bpmTolerancePct must be non-negative")
    @DecimalMax(value = "100.0", message = "bpmTolerancePct cannot exceed 100")
    private BigDecimal bpmTolerancePct;

    private Boolean soundclashEnabled;

    private String aiVerbosity;
}
