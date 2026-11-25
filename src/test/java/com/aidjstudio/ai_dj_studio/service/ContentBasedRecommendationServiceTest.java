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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContentBasedRecommendationServiceTest {

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private InteractionRepository interactionRepository;

    @Spy
    private AcousticMatcherService acousticMatcherService = new AcousticMatcherService();

    @InjectMocks
    private ContentBasedRecommendationService recommendationService;

    private Track trackA;
    private Track trackB;
    private Track trackC;
    private Track trackD;

    @BeforeEach
    void setUp() {
        // Track A: Baseline House (124 BPM, 8A, 0.80 Energy, 0.60 Valence)
        trackA = new Track(1L, "Deep Pulse", "DJ Alpha", 200, "http://asset/1",
                BigDecimal.valueOf(124.0), "8A", BigDecimal.valueOf(0.80), BigDecimal.valueOf(0.60));

        // Track B: Adjacent Harmonic (126 BPM, 9A, 0.75 Energy, 0.55 Valence)
        trackB = new Track(2L, "Solar Echo", "DJ Beta", 210, "http://asset/2",
                BigDecimal.valueOf(126.0), "9A", BigDecimal.valueOf(0.75), BigDecimal.valueOf(0.55));

        // Track C: Half-time Dub (62 BPM [half of 124], 8B [relative major], 0.40 Energy, 0.30 Valence)
        trackC = new Track(3L, "Midnight Chill", "DJ Gamma", 180, "http://asset/3",
                BigDecimal.valueOf(62.0), "8B", BigDecimal.valueOf(0.40), BigDecimal.valueOf(0.30));

        // Track D: Far BPM & Clashing Key (174 BPM, 2A, 0.95 Energy, 0.90 Valence)
        trackD = new Track(4L, "Hyper Rush", "DJ Delta", 160, "http://asset/4",
                BigDecimal.valueOf(174.0), "2A", BigDecimal.valueOf(0.95), BigDecimal.valueOf(0.90));
    }

    @Nested
    @DisplayName("Similarity Formula & Scoring Tests")
    class ScoringFormulaTests {

        @Test
        @DisplayName("Exact match candidate yields maximum score 1.0")
        void exactMatch_yieldsScoreOne() {
            UserTasteProfile profile = UserTasteProfile.builder()
                    .averageBpm(BigDecimal.valueOf(124.0))
                    .dominantCamelotKey("8A")
                    .averageEnergy(BigDecimal.valueOf(0.80))
                    .averageValence(BigDecimal.valueOf(0.60))
                    .build();

            RecommendationScoreBreakdown breakdown = recommendationService.scoreCandidate(profile, trackA);

            assertThat(breakdown.getTotalScore()).isEqualTo(1.0);
            assertThat(breakdown.getBpmScore()).isEqualTo(1.0);
            assertThat(breakdown.getKeyScore()).isEqualTo(1.0);
            assertThat(breakdown.getEnergyScore()).isEqualTo(1.0);
            assertThat(breakdown.getValenceScore()).isEqualTo(1.0);
            assertThat(breakdown.getKeyRelation()).contains("SAME_KEY");
        }

        @Test
        @DisplayName("Adjacent key (9A) and slight delta yields high harmonic score (0.85)")
        void adjacentKey_yieldsCorrectBreakdown() {
            UserTasteProfile profile = UserTasteProfile.builder()
                    .averageBpm(BigDecimal.valueOf(124.0))
                    .dominantCamelotKey("8A")
                    .averageEnergy(BigDecimal.valueOf(0.80))
                    .averageValence(BigDecimal.valueOf(0.60))
                    .build();

            RecommendationScoreBreakdown breakdown = recommendationService.scoreCandidate(profile, trackB);

            // BPM delta = 2.0 -> bpmScore = 1.0 - (2.0 / 50.0) = 0.96
            assertThat(breakdown.getBpmScore()).isEqualTo(0.96);
            assertThat(breakdown.getKeyScore()).isEqualTo(0.85);
            assertThat(breakdown.getKeyRelation()).contains("ADJACENT_KEY");
            // Energy delta = |0.75 - 0.80| = 0.05 -> energyScore = 0.95
            assertThat(breakdown.getEnergyScore()).isEqualTo(0.95);
            // Valence delta = |0.55 - 0.60| = 0.05 -> valenceScore = 0.95
            assertThat(breakdown.getValenceScore()).isEqualTo(0.95);

            // Total = (0.96 * 0.3) + (0.85 * 0.3) + (0.95 * 0.2) + (0.95 * 0.2)
            // = 0.288 + 0.255 + 0.190 + 0.190 = 0.923
            assertThat(breakdown.getTotalScore()).isEqualTo(0.923);
        }

        @Test
        @DisplayName("Half-time BPM (62 vs 124) is recognized with 0 delta")
        void halfTimeBpm_recognizedWithZeroDelta() {
            UserTasteProfile profile = UserTasteProfile.builder()
                    .averageBpm(BigDecimal.valueOf(124.0))
                    .dominantCamelotKey("8A")
                    .averageEnergy(BigDecimal.valueOf(0.40))
                    .averageValence(BigDecimal.valueOf(0.30))
                    .build();

            RecommendationScoreBreakdown breakdown = recommendationService.scoreCandidate(profile, trackC);

            assertThat(breakdown.getBpmScore()).isEqualTo(1.0);
            assertThat(breakdown.getBpmDelta()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(breakdown.getKeyRelation()).contains("RELATIVE_KEY");
            assertThat(breakdown.getKeyScore()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("Distant BPM and key yields lowest total score")
        void distantTrack_yieldsLowScore() {
            UserTasteProfile profile = UserTasteProfile.builder()
                    .averageBpm(BigDecimal.valueOf(124.0))
                    .dominantCamelotKey("8A")
                    .averageEnergy(BigDecimal.valueOf(0.80))
                    .averageValence(BigDecimal.valueOf(0.60))
                    .build();

            RecommendationScoreBreakdown breakdown = recommendationService.scoreCandidate(profile, trackD);

            assertThat(breakdown.getTotalScore()).isLessThan(0.60);
        }
    }

    @Nested
    @DisplayName("User Taste Profile & Cold Start Tests")
    class TasteProfileTests {

        @Test
        @DisplayName("Cold-start user with no interactions gets catalog average profile")
        void coldStart_returnsCatalogAverage() {
            when(interactionRepository.findByUserIdOrderByCreatedAtDesc(100L)).thenReturn(Flux.empty());
            when(trackRepository.findAll()).thenReturn(Flux.just(trackA, trackB));

            StepVerifier.create(recommendationService.getUserTasteProfile(100L))
                    .assertNext(profile -> {
                        assertThat(profile.isColdStart()).isTrue();
                        assertThat(profile.getPositiveInteractionCount()).isEqualTo(0);
                        // Avg BPM: (124 + 126) / 2 = 125.0
                        assertThat(profile.getAverageBpm()).isEqualByComparingTo("125.0");
                        // Avg Energy: (0.80 + 0.75) / 2 = 0.78
                        assertThat(profile.getAverageEnergy()).isEqualByComparingTo("0.78");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("User with liked and completed tracks computes weighted profile")
        void activeUser_computesWeightedProfile() {
            Interaction likeA = Interaction.builder()
                    .id(1L).userId(100L).trackId(1L).liked(true).completed(true).playedSeconds(190).createdAt(LocalDateTime.now()).build();
            Interaction playB = Interaction.builder()
                    .id(2L).userId(100L).trackId(2L).liked(false).completed(false).playedSeconds(50).createdAt(LocalDateTime.now()).build();

            when(interactionRepository.findByUserIdOrderByCreatedAtDesc(100L)).thenReturn(Flux.just(likeA, playB));
            when(trackRepository.findAllById(any(Iterable.class))).thenReturn(Flux.just(trackA, trackB));

            StepVerifier.create(recommendationService.getUserTasteProfile(100L))
                    .assertNext(profile -> {
                        assertThat(profile.isColdStart()).isFalse();
                        assertThat(profile.getPositiveInteractionCount()).isEqualTo(2);
                        assertThat(profile.getDominantCamelotKey()).isEqualTo("8A"); // Liked track A has higher weight
                        assertThat(profile.getAverageBpm()).isNotNull();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Personalized and Seed Track Recommendations")
    class RecommendationExecutionTests {

        @Test
        @DisplayName("Personalized recommendation ranks candidates and excludes interacted tracks")
        void personalizedRecommendations_excludesInteractedWhenRequested() {
            Interaction likeA = Interaction.builder()
                    .id(1L).userId(100L).trackId(1L).liked(true).completed(true).playedSeconds(190).createdAt(LocalDateTime.now()).build();

            when(interactionRepository.findByUserIdOrderByCreatedAtDesc(100L)).thenReturn(Flux.just(likeA));
            when(trackRepository.findAllById(any(Iterable.class))).thenReturn(Flux.just(trackA));
            when(trackRepository.findAll()).thenReturn(Flux.just(trackA, trackB, trackC, trackD));

            StepVerifier.create(recommendationService.getPersonalizedRecommendations(100L, 5, true))
                    .assertNext(rail -> {
                        assertThat(rail.getStrategy()).isEqualTo("CONTENT_BASED_PERSONALIZED");
                        assertThat(rail.getTracks()).isNotEmpty();
                        // Track A was interacted, so it should be excluded
                        assertThat(rail.getTracks()).noneMatch(t -> t.getTrackId().equals(1L));
                        // Track B should be rank 1 (closest to Track A's profile)
                        assertThat(rail.getTracks().get(0).getTrackId()).isEqualTo(2L);
                        assertThat(rail.getTracks().get(0).getRank()).isEqualTo(1);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Seed-track recommendations exclude seed track and order by similarity")
        void seedTrackRecommendations_excludeSeedTrackAndRank() {
            when(trackRepository.findById(1L)).thenReturn(Mono.just(trackA));
            when(trackRepository.findAll()).thenReturn(Flux.just(trackA, trackB, trackC, trackD));

            StepVerifier.create(recommendationService.getTrackRecommendations(1L, 10))
                    .assertNext(rail -> {
                        assertThat(rail.getStrategy()).isEqualTo("CONTENT_BASED_SEED_TRACK");
                        assertThat(rail.getSeedTrackId()).isEqualTo(1L);
                        assertThat(rail.getTracks()).noneMatch(t -> t.getTrackId().equals(1L));
                        // Track B is closest to Track A
                        assertThat(rail.getTracks().get(0).getTrackId()).isEqualTo(2L);
                        assertThat(rail.getTracks().get(0).getRank()).isEqualTo(1);
                    })
                    .verifyComplete();
        }
    }
}
