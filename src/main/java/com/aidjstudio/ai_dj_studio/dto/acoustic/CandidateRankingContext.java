package com.aidjstudio.ai_dj_studio.dto.acoustic;

import com.aidjstudio.ai_dj_studio.dto.sentiment.RoomSentimentSnapshot;
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

    /**
     * Rolling room sentiment snapshot from F12.
     * When provided, influences target energy by shifting it up (positive) or down (negative).
     */
    private RoomSentimentSnapshot roomSentiment;

    /**
     * Direct sentiment score in [-1.0, +1.0].
     * Can be passed directly or extracted from roomSentiment.
     */
    private Double sentimentScore;
}
