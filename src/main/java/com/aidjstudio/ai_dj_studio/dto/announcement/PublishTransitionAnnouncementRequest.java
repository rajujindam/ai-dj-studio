package com.aidjstudio.ai_dj_studio.dto.announcement;

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
public class PublishTransitionAnnouncementRequest {
    private List<Long> candidateTrackIds;
    private BigDecimal targetEnergy;
    private Double bpmTolerancePct;
}
