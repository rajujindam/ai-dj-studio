package com.aidjstudio.ai_dj_studio.controller;

import com.aidjstudio.ai_dj_studio.dto.recommendation.RecommendationRailResponse;
import com.aidjstudio.ai_dj_studio.dto.recommendation.UserTasteProfile;
import com.aidjstudio.ai_dj_studio.service.ContentBasedRecommendationService;
import com.aidjstudio.ai_dj_studio.service.HybridRecommendationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final HybridRecommendationService hybridRecommendationService;
    private final ContentBasedRecommendationService contentRecommendationService;

    @GetMapping("/personalized")
    public Mono<ResponseEntity<RecommendationRailResponse>> getPersonalizedRecommendations(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "true") boolean excludeInteracted,
            @RequestParam(defaultValue = "HYBRID") String strategy,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        if ("CONTENT_BASED".equalsIgnoreCase(strategy)) {
            return contentRecommendationService
                    .getPersonalizedRecommendations(authenticatedUserId, limit, excludeInteracted)
                    .map(ResponseEntity::ok);
        }

        return hybridRecommendationService
                .getHybridPersonalizedRecommendations(authenticatedUserId, limit, excludeInteracted)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/tracks/{trackId}")
    public Mono<ResponseEntity<RecommendationRailResponse>> getTrackRecommendations(
            @PathVariable Long trackId,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return contentRecommendationService
                .getTrackRecommendations(trackId, limit)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/taste-profile")
    public Mono<ResponseEntity<UserTasteProfile>> getUserTasteProfile(
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return contentRecommendationService
                .getUserTasteProfile(authenticatedUserId)
                .map(ResponseEntity::ok);
    }
}
