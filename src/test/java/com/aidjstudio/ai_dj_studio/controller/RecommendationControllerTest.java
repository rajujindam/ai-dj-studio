package com.aidjstudio.ai_dj_studio.controller;

import com.aidjstudio.ai_dj_studio.dto.recommendation.RecommendationRailResponse;
import com.aidjstudio.ai_dj_studio.dto.recommendation.RecommendedTrackResponse;
import com.aidjstudio.ai_dj_studio.dto.recommendation.UserTasteProfile;
import com.aidjstudio.ai_dj_studio.service.ContentBasedRecommendationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendationControllerTest {

    @Mock
    private ContentBasedRecommendationService recommendationService;

    private RecommendationController controller;
    private Jwt testJwt;

    @BeforeEach
    void setUp() {
        controller = new RecommendationController(recommendationService);
        testJwt = mock(Jwt.class);
        lenient().when(testJwt.getSubject()).thenReturn("100");
    }

    @Test
    @DisplayName("GET /api/v1/recommendations/personalized returns 200 with recommended tracks")
    void getPersonalizedRecommendations_returns200() {
        RecommendedTrackResponse trackResponse = RecommendedTrackResponse.builder()
                .rank(1)
                .trackId(2L)
                .title("Solar Echo")
                .artist("DJ Beta")
                .similarityScore(0.923)
                .reason("Match score 0.92")
                .build();

        RecommendationRailResponse rail = RecommendationRailResponse.builder()
                .strategy("CONTENT_BASED_PERSONALIZED")
                .userId(100L)
                .totalRecommendations(1)
                .tracks(List.of(trackResponse))
                .build();

        when(recommendationService.getPersonalizedRecommendations(100L, 10, true))
                .thenReturn(Mono.just(rail));

        StepVerifier.create(controller.getPersonalizedRecommendations(10, true, testJwt))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody()).isNotNull();
                    assertThat(response.getBody().getStrategy()).isEqualTo("CONTENT_BASED_PERSONALIZED");
                    assertThat(response.getBody().getTracks()).hasSize(1);
                    assertThat(response.getBody().getTracks().get(0).getTitle()).isEqualTo("Solar Echo");
                })
                .verifyComplete();

        verify(recommendationService).getPersonalizedRecommendations(100L, 10, true);
    }

    @Test
    @DisplayName("GET /api/v1/recommendations/tracks/{trackId} returns 200 with seed-track rail")
    void getTrackRecommendations_returns200() {
        RecommendationRailResponse rail = RecommendationRailResponse.builder()
                .strategy("CONTENT_BASED_SEED_TRACK")
                .seedTrackId(1L)
                .totalRecommendations(1)
                .tracks(List.of(RecommendedTrackResponse.builder().rank(1).trackId(2L).build()))
                .build();

        when(recommendationService.getTrackRecommendations(1L, 5))
                .thenReturn(Mono.just(rail));

        StepVerifier.create(controller.getTrackRecommendations(1L, 5))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody()).isNotNull();
                    assertThat(response.getBody().getSeedTrackId()).isEqualTo(1L);
                })
                .verifyComplete();

        verify(recommendationService).getTrackRecommendations(1L, 5);
    }

    @Test
    @DisplayName("GET /api/v1/recommendations/taste-profile returns 200 with taste profile")
    void getUserTasteProfile_returns200() {
        UserTasteProfile profile = UserTasteProfile.builder()
                .userId(100L)
                .averageBpm(BigDecimal.valueOf(124.0))
                .dominantCamelotKey("8A")
                .averageEnergy(BigDecimal.valueOf(0.75))
                .averageValence(BigDecimal.valueOf(0.60))
                .coldStart(false)
                .build();

        when(recommendationService.getUserTasteProfile(100L))
                .thenReturn(Mono.just(profile));

        StepVerifier.create(controller.getUserTasteProfile(testJwt))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody()).isNotNull();
                    assertThat(response.getBody().getDominantCamelotKey()).isEqualTo("8A");
                })
                .verifyComplete();

        verify(recommendationService).getUserTasteProfile(100L);
    }
}
