package com.aidjstudio.ai_dj_studio.dto.soundclash;

import com.aidjstudio.ai_dj_studio.dto.track.TrackResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SoundclashCandidateResponse {

    private Long id;
    private Long battleId;
    private Long trackId;
    private Long submittedByUserId;
    private Integer voteCount;
    private TrackResponse track;
}
