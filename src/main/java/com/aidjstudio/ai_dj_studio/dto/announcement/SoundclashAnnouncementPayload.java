package com.aidjstudio.ai_dj_studio.dto.announcement;

import com.aidjstudio.ai_dj_studio.dto.track.RetrievedMetadataEntry;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SoundclashAnnouncementPayload {
    @Builder.Default
    private String announcementType = "SOUNDCLASH";
    private Long loungeId;
    private Long battleId;
    private Long winningTrackId;
    private String winningTrackTitle;
    private String winningTrackArtist;
    private Long winningCandidateId;
    private Integer winnerVoteCount;
    private Integer totalVotes;
    private List<SoundclashCandidateSummary> candidates;
    private String script;
    private List<String> sourceIds;
    private List<RetrievedMetadataEntry> sources;
    private Instant createdAt;
}
