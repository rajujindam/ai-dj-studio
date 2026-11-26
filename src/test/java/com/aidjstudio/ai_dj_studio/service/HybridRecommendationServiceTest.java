package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.recommendation.RecommendationRailResponse;
import com.aidjstudio.ai_dj_studio.dto.recommendation.RecommendationScoreBreakdown;
import com.aidjstudio.ai_dj_studio.dto.recommendation.RecommendedTrackResponse;
import com.aidjstudio.ai_dj_studio.dto.recommendation.UserTasteProfile;
import com.aidjstudio.ai_dj_studio.entity.Interaction;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.repository.InteractionRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HybridRecommendationServiceTest {

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private InteractionRepository interactionRepository;

    @Mock
    private ContentBasedRecommendationService contentRecommendationService;

    @Mock
    private CollaborativeFilteringService collaborativeFilteringService;

    @InjectMocks
    private HybridRecommendationService hybridRecommendationService;

    private Track track1;
    private Track track2;
    private Track track3;

    @BeforeEach
    void setUp() {
        track1 = new Track(1L, "Solar Echo", "DJ Alpha", 200, "http://asset/1",
                BigDecimal.valueOf(124.0), "8A", BigDecimal.valueOf(0.80), BigDecimal.valueOf(0.60));
        track2 = new Track(2L, "Deep Pulse", "DJ Beta", 210, "http://asset/2",
                BigDecimal.valueOf(126.0), "9A", BigDecimal.valueOf(0.75), BigDecimal.valueOf(0.55));
        track3 = new Track(3L, "Neon Horizon", "DJ Gamma", 195, "http://asset/3",
                BigDecimal.valueOf(128.0), "8B", BigDecimal.valueOf(0.85), BigDecimal.valueOf(0.70));
    }

    @Nested
    @DisplayName("Hybrid Combination & Weighting Tests")
    class CombinationTests {

        @Test
        @DisplayName("Combines content score (50%) and collaborative score (50%) into balanced hybrid score")
        void balancedHybridScoring_computesCorrectWeights() {
            UserTasteProfile profile = UserTasteProfile.builder()
                    .userId(10L)
                    .averageBpm(BigDecimal.valueOf(124.0))
                    .dominantCamelotKey("8A")
                    .averageEnergy(BigDecimal.valueOf(0.80))
                    .averageValence(BigDecimal.valueOf(0.60))
                    .coldStart(false)
                    .build();

            // Mock content scoring: track1 = 0.90, track2 = 0.70
            RecommendationScoreBreakdown breakdown1 = RecommendationScoreBreakdown.builder()
                    .totalScore(0.90)
                    .bpmScore(0.95).keyScore(0.90).energyScore(0.85).valenceScore(0.85)
                    .keyRelation("SAME_KEY")
                    .reason("High acoustic fit")
                    .build();

            RecommendationScoreBreakdown breakdown2 = RecommendationScoreBreakdown.builder()
                    .totalScore(0.70)
                    .bpmScore(0.80).keyScore(0.70).energyScore(0.65).valenceScore(0.65)
                    .keyRelation("ADJACENT_KEY")
                    .reason("Moderate acoustic fit")
                    .build();

            when(contentRecommendationService.scoreCandidate(eq(profile), eq(track1))).thenReturn(breakdown1);
            when(contentRecommendationService.scoreCandidate(eq(profile), eq(track2))).thenReturn(breakdown2);

            // Mock collaborative scores: track1 = 0.60, track2 = 0.90
            Map<Long, CollaborativeFilteringService.CollaborativeScoreResult> collabScores = Map.of(
                    1L, new CollaborativeFilteringService.CollaborativeScoreResult(0.60, "Shared with 2 listeners", 2, true),
                    2L, new CollaborativeFilteringService.CollaborativeScoreResult(0.90, "Shared with 5 listeners", 5, true)
            );

            List<RecommendedTrackResponse> ranked = hybridRecommendationService.rankHybridCandidates(
                    profile,
                    List.of(track1, track2),
                    collabScores,
                    10,
                    null
            );

            // Track 1: (0.90 * 0.5) + (0.60 * 0.5) = 0.45 + 0.30 = 0.75
            // Track 2: (0.70 * 0.5) + (0.90 * 0.5) = 0.35 + 0.45 = 0.80
            // Therefore, Track 2 ranks #1 (0.80) and Track 1 ranks #2 (0.75)!
            assertThat(ranked).hasSize(2);
            assertThat(ranked.get(0).getTrackId()).isEqualTo(2L);
            assertThat(ranked.get(0).getSimilarityScore()).isEqualTo(0.80);
            assertThat(ranked.get(0).getRank()).isEqualTo(1);
            assertThat(ranked.get(0).getScoreBreakdown().getContentScore()).isEqualTo(0.70);
            assertThat(ranked.get(0).getScoreBreakdown().getCollaborativeScore()).isEqualTo(0.90);
            assertThat(ranked.get(0).getScoreBreakdown().getContentWeight()).isEqualTo(0.50);
            assertThat(ranked.get(0).getScoreBreakdown().getCollaborativeWeight()).isEqualTo(0.50);

            assertThat(ranked.get(1).getTrackId()).isEqualTo(1L);
            assertThat(ranked.get(1).getSimilarityScore()).isEqualTo(0.75);
            assertThat(ranked.get(1).getRank()).isEqualTo(2);
        }

        @Test
        @DisplayName("Cold-start user gracefully falls back to 100% content score")
        void coldStartUser_fallsBackToContentScore() {
            UserTasteProfile coldProfile = UserTasteProfile.builder()
                    .userId(99L)
                    .averageBpm(BigDecimal.valueOf(124.0))
                    .dominantCamelotKey("8A")
                    .averageEnergy(BigDecimal.valueOf(0.70))
                    .averageValence(BigDecimal.valueOf(0.50))
                    .coldStart(true)
                    .build();

            RecommendationScoreBreakdown breakdown = RecommendationScoreBreakdown.builder()
                    .totalScore(0.85)
                    .bpmScore(0.85).keyScore(0.85).energyScore(0.85).valenceScore(0.85)
                    .keyRelation("SAME_KEY")
                    .reason("Catalog default match")
                    .build();

            when(contentRecommendationService.scoreCandidate(eq(coldProfile), eq(track1))).thenReturn(breakdown);

            Map<Long, CollaborativeFilteringService.CollaborativeScoreResult> emptyCollab = Map.of(
                    1L, new CollaborativeFilteringService.CollaborativeScoreResult(0.0, "Cold start user", 0, false)
            );

            List<RecommendedTrackResponse> ranked = hybridRecommendationService.rankHybridCandidates(
                    coldProfile,
                    List.of(track1),
                    emptyCollab,
                    5,
                    null
            );

            assertThat(ranked).hasSize(1);
            assertThat(ranked.get(0).getSimilarityScore()).isEqualTo(0.85);
            assertThat(ranked.get(0).getScoreBreakdown().getContentWeight()).isEqualTo(1.0);
            assertThat(ranked.get(0).getScoreBreakdown().getCollaborativeWeight()).isEqualTo(0.0);
            assertThat(ranked.get(0).getReason()).contains("Cold-start fallback");
        }
    }

    @Nested
    @DisplayName("End-to-End Reactive Pipeline Tests")
    class PipelineTests {

        @Test
        @DisplayName("getHybridPersonalizedRecommendations returns ranked rail with strategy HYBRID_PERSONALIZED")
        void getHybridPersonalizedRecommendations_success() {
            Long userId = 10L;
            UserTasteProfile profile = UserTasteProfile.builder()
                    .userId(userId)
                    .averageBpm(BigDecimal.valueOf(124.0))
                    .dominantCamelotKey("8A")
                    .averageEnergy(BigDecimal.valueOf(0.80))
                    .averageValence(BigDecimal.valueOf(0.60))
                    .coldStart(false)
                    .build();

            Interaction userInter = Interaction.builder()
                    .userId(userId).trackId(1L).liked(true).completed(true).playedSeconds(180).build();

            when(contentRecommendationService.getUserTasteProfile(userId)).thenReturn(Mono.just(profile));
            when(interactionRepository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(Flux.just(userInter));
            when(contentRecommendationService.isPositiveInteraction(any(Interaction.class))).thenReturn(true);
            when(trackRepository.findAll()).thenReturn(Flux.just(track1, track2, track3));

            // Candidate pool excludes track1 (interacted), so candidates are track2 and track3
            when(collaborativeFilteringService.computeCollaborativeScores(eq(userId), anyList()))
                    .thenReturn(Mono.just(Map.of(
                            2L, new CollaborativeFilteringService.CollaborativeScoreResult(0.80, "Shared listeners", 3, true),
                            3L, new CollaborativeFilteringService.CollaborativeScoreResult(0.40, "Low overlap", 1, true)
                    )));

            RecommendationScoreBreakdown breakdown2 = RecommendationScoreBreakdown.builder()
                    .totalScore(0.80).bpmScore(0.8).keyScore(0.8).energyScore(0.8).valenceScore(0.8)
                    .keyRelation("SAME_KEY").reason("Good acoustic fit").build();
            RecommendationScoreBreakdown breakdown3 = RecommendationScoreBreakdown.builder()
                    .totalScore(0.60).bpmScore(0.6).keyScore(0.6).energyScore(0.6).valenceScore(0.6)
                    .keyRelation("ADJACENT_KEY").reason("Moderate acoustic fit").build();

            when(contentRecommendationService.scoreCandidate(eq(profile), eq(track2))).thenReturn(breakdown2);
            when(contentRecommendationService.scoreCandidate(eq(profile), eq(track3))).thenReturn(breakdown3);

            StepVerifier.create(hybridRecommendationService.getHybridPersonalizedRecommendations(userId, 5, true))
                    .assertNext(rail -> {
                        assertThat(rail).isNotNull();
                        assertThat(rail.getStrategy()).isEqualTo("HYBRID_PERSONALIZED");
                        assertThat(rail.getUserId()).isEqualTo(userId);
                        assertThat(rail.getTracks()).hasSize(2);
                        assertThat(rail.getTracks().get(0).getTrackId()).isEqualTo(2L);
                        assertThat(rail.getTracks().get(0).getRank()).isEqualTo(1);
                        assertThat(rail.getTracks().get(0).getSimilarityScore()).isEqualTo(0.80);
                    })
                    .verifyComplete();
        }
    }
}
