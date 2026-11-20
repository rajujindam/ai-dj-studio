package com.aidjstudio.ai_dj_studio.dto.acoustic;

import com.aidjstudio.ai_dj_studio.entity.Track;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CandidateRankingContext {

    private Track currentTrack;
    @Builder.Default
    private List<Track> candidates = Collections.emptyList();
    @Builder.Default
    private List<Long> recentTrackIds = Collections.emptyList();
    private BigDecimal targetEnergy;
    @Builder.Default
    private Double bpmTolerancePct = 8.0;
}
