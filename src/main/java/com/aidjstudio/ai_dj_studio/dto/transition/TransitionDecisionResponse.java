package com.aidjstudio.ai_dj_studio.dto.transition;

import com.aidjstudio.ai_dj_studio.dto.track.RetrievedMetadataEntry;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransitionDecisionResponse {

    private boolean seguePossible;
    private Long currentTrackId;
    private String currentTrackTitle;
    private Long selectedTrackId;
    private String selectedTrackTitle;
    private String selectedTrackArtist;
    private BigDecimal bpmDelta;
    private String camelotRelation;
    private String rankingExplanation;
    private String script;
    private List<String> sourceIds;
    private List<RetrievedMetadataEntry> sources;
    private Instant generatedAt;
}
