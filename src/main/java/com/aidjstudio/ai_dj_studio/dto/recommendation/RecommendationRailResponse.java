package com.aidjstudio.ai_dj_studio.dto.recommendation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationRailResponse {

    private String strategy;
    private Long userId;
    private Long seedTrackId;
    private UserTasteProfile tasteProfile;
    private int totalRecommendations;
    private List<RecommendedTrackResponse> tracks;
}
