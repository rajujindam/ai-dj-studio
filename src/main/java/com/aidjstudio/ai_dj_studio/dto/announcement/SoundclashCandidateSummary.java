package com.aidjstudio.ai_dj_studio.dto.announcement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SoundclashCandidateSummary {
    private Long candidateId;
    private Long trackId;
    private String trackTitle;
    private String trackArtist;
    private Integer voteCount;
}
