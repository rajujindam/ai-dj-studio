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
public class AcousticMatchResult {

    private boolean compatible;
    private boolean bpmCompatible;
    private boolean camelotCompatible;

    private BpmRelation bpmRelation;
    private HarmonicRelation harmonicRelation;

    private BigDecimal currentBpm;
    private BigDecimal candidateBpm;
    private BigDecimal bpmDelta;
    private BigDecimal bpmDeltaPercent;

    private String currentCamelotKey;
    private String candidateCamelotKey;

    private String reason;
}
