package com.aidjstudio.ai_dj_studio.dto.announcement;

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
public class TransitionAnnouncementPayload {
    @Builder.Default
    private String announcementType = "TRANSITION";
    private Long loungeId;
    private Long currentTrackId;
    private String currentTrackTitle;
    private String currentTrackArtist;
    private Long nextTrackId;
    private String nextTrackTitle;
    private String nextTrackArtist;
    private BigDecimal bpmDelta;
    private String camelotRelation;
    private String rankingExplanation;
    private String script;
    private List<String> sourceIds;
    private List<RetrievedMetadataEntry> sources;
    private Instant createdAt;
}
