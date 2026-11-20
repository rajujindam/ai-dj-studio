package com.aidjstudio.ai_dj_studio.dto.acoustic;

import com.aidjstudio.ai_dj_studio.entity.Track;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RankedCandidate {

    private int rank;
    private Track track;
    private RankingScoreBreakdown scoreBreakdown;
    private AcousticMatchResult matchResult;
}
