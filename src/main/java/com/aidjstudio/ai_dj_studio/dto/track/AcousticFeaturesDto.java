package com.aidjstudio.ai_dj_studio.dto.track;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AcousticFeaturesDto {
    private BigDecimal bpm;
    private String camelotKey;
    private BigDecimal energy;
    private BigDecimal valence;
}
