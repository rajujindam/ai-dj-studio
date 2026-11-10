package com.aidjstudio.ai_dj_studio.dto.track;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrackAcousticProfileResponse {
    private Long trackId;
    private String title;
    private String artist;
    private AcousticFeaturesDto acoustic;
    private List<TimelineEventDto> timeline;
}
