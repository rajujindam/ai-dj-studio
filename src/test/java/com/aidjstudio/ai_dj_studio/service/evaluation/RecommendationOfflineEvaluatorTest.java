package com.aidjstudio.ai_dj_studio.service.evaluation;

import com.aidjstudio.ai_dj_studio.repository.InteractionRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.service.AcousticMatcherService;
import com.aidjstudio.ai_dj_studio.service.CollaborativeFilteringService;
import com.aidjstudio.ai_dj_studio.service.ContentBasedRecommendationService;
import com.aidjstudio.ai_dj_studio.service.HybridRecommendationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RecommendationOfflineEvaluatorTest {

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private InteractionRepository interactionRepository;

    private RecommendationOfflineEvaluator evaluator;

    @BeforeEach
    void setUp() {
        AcousticMatcherService acousticMatcherService = new AcousticMatcherService();
        ContentBasedRecommendationService contentService = new ContentBasedRecommendationService(
                trackRepository, interactionRepository, acousticMatcherService
        );
        CollaborativeFilteringService collabService = new CollaborativeFilteringService(interactionRepository);
        HybridRecommendationService hybridService = new HybridRecommendationService(
                trackRepository, interactionRepository, contentService, collabService
        );

        evaluator = new RecommendationOfflineEvaluator(contentService, collabService, hybridService);
    }

    @Test
    @DisplayName("Offline Evaluation Harness achieves Precision@5 >= 0.60 on held-out benchmark split")
    void evaluatePrecisionAt5_meetsPrdTarget() {
        RecommendationOfflineEvaluator.EvaluationResult result = evaluator.evaluatePrecisionAtK(5);

        System.out.println("=== OFFLINE EVALUATION RESULTS ===");
        System.out.println("Dataset summary: " + result.getDatasetSummary());
        System.out.println("Evaluated users: " + result.getEvaluatedUsers());
        System.out.println("Measured Precision@5: " + result.getPrecisionAtK());
        System.out.println("Measured HitRate@5: " + result.getHitRateAtK());
        System.out.println("Per-user Precision: " + result.getPerUserPrecision());

        assertThat(result.getEvaluatedUsers()).isGreaterThan(0);
        // PRD KPI acceptance criteria: Precision@5 >= 0.60
        assertThat(result.getPrecisionAtK())
                .as("Precision@5 must satisfy the PRD threshold of >= 0.60 on held-out dataset")
                .isGreaterThanOrEqualTo(0.60);

        assertThat(result.getHitRateAtK()).isGreaterThanOrEqualTo(0.80);
    }
}
