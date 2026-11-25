package com.aidjstudio.ai_dj_studio.dto.interaction;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrackLikeRequest {

    @Builder.Default
    private Boolean liked = true;

    private Long loungeId;
}
