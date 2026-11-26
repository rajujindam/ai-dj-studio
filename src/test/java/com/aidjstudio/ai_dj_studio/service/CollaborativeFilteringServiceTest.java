package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.entity.Interaction;
import com.aidjstudio.ai_dj_studio.repository.InteractionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollaborativeFilteringServiceTest {

    @Mock
    private InteractionRepository interactionRepository;

    @InjectMocks
    private CollaborativeFilteringService collaborativeFilteringService;

    private List<Interaction> testInteractions;

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.now();

        // User 10 likes Track 1 and Track 2 in Lounge 100
        Interaction i1 = Interaction.builder().id(1L).userId(10L).trackId(1L).loungeId(100L).liked(true).completed(true).playedSeconds(180).createdAt(now).build();
        Interaction i2 = Interaction.builder().id(2L).userId(10L).trackId(2L).loungeId(100L).liked(true).completed(true).playedSeconds(200).createdAt(now).build();

        // User 20 likes Track 2 and Track 3 in Lounge 100
        Interaction i3 = Interaction.builder().id(3L).userId(20L).trackId(2L).loungeId(100L).liked(true).completed(true).playedSeconds(210).createdAt(now).build();
        Interaction i4 = Interaction.builder().id(4L).userId(20L).trackId(3L).loungeId(100L).liked(true).completed(false).playedSeconds(90).createdAt(now).build();

        // User 30 likes Track 1 and Track 3 in Lounge 200
        Interaction i5 = Interaction.builder().id(5L).userId(30L).trackId(1L).loungeId(200L).liked(true).completed(true).playedSeconds(190).createdAt(now).build();
        Interaction i6 = Interaction.builder().id(6L).userId(30L).trackId(3L).loungeId(200L).liked(true).completed(true).playedSeconds(190).createdAt(now).build();

        testInteractions = List.of(i1, i2, i3, i4, i5, i6);
    }

    @Nested
    @DisplayName("Co-Listening & Similarity Calculations")
    class SimilarityCalculations {

        @Test
        @DisplayName("Computes collaborative affinity for candidate with shared listeners and lounge co-listening")
        void candidateWithSharedListeners_receivesHighAffinity() {
            // Target user 10 (interacted with Track 1 and 2). Candidate is Track 3.
            Map<Long, CollaborativeFilteringService.CollaborativeScoreResult> scores =
                    collaborativeFilteringService.computeCollaborativeScoresFromInteractions(
                            10L,
                            List.of(3L),
                            testInteractions
                    );

            assertThat(scores).containsKey(3L);
            CollaborativeFilteringService.CollaborativeScoreResult result = scores.get(3L);
            assertThat(result.hasSignal()).isTrue();
            assertThat(result.score()).isGreaterThan(0.40);
            assertThat(result.coListeningCount()).isGreaterThanOrEqualTo(1);
            assertThat(result.reason()).contains("Co-listening affinity");
        }

        @Test
        @DisplayName("Reactively computes collaborative scores using repository findAll()")
        void computeCollaborativeScores_reactive() {
            when(interactionRepository.findAll()).thenReturn(Flux.fromIterable(testInteractions));

            StepVerifier.create(collaborativeFilteringService.computeCollaborativeScores(10L, List.of(3L)))
                    .assertNext(scores -> {
                        assertThat(scores).containsKey(3L);
                        assertThat(scores.get(3L).hasSignal()).isTrue();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Cold-Start & Sparse Interaction Tests")
    class ColdStartAndSparseTests {

        @Test
        @DisplayName("Cold start user with zero interactions receives score 0.0 with clear diagnostic")
        void coldStartUser_returnsZeroWithDiagnostic() {
            Long newUserId = 999L;
            Map<Long, CollaborativeFilteringService.CollaborativeScoreResult> scores =
                    collaborativeFilteringService.computeCollaborativeScoresFromInteractions(
                            newUserId,
                            List.of(1L, 2L, 3L),
                            testInteractions
                    );

            for (Long trackId : List.of(1L, 2L, 3L)) {
                assertThat(scores).containsKey(trackId);
                CollaborativeFilteringService.CollaborativeScoreResult res = scores.get(trackId);
                assertThat(res.score()).isEqualTo(0.0);
                assertThat(res.hasSignal()).isFalse();
                assertThat(res.reason()).contains("Cold start user");
            }
        }

        @Test
        @DisplayName("Sparse candidate track with no co-listeners receives score 0.0 with sparse item reason")
        void sparseCandidateTrack_returnsZeroWithReason() {
            Long unseenTrackId = 555L;
            Map<Long, CollaborativeFilteringService.CollaborativeScoreResult> scores =
                    collaborativeFilteringService.computeCollaborativeScoresFromInteractions(
                            10L,
                            List.of(unseenTrackId),
                            testInteractions
                    );

            assertThat(scores).containsKey(unseenTrackId);
            CollaborativeFilteringService.CollaborativeScoreResult res = scores.get(unseenTrackId);
            assertThat(res.score()).isEqualTo(0.0);
            assertThat(res.hasSignal()).isFalse();
            assertThat(res.reason()).contains("sparse item");
        }

        @Test
        @DisplayName("Single sparse interaction user does not crash and computes available affinity")
        void sparseUser_singleInteraction_computesSuccessfully() {
            // User 40 has only 1 positive interaction on Track 1
            Interaction singleIn = Interaction.builder()
                    .userId(40L)
                    .trackId(1L)
                    .playedSeconds(45)
                    .completed(false)
                    .liked(false)
                    .build();

            List<Interaction> interactionsWithUser40 = new java.util.ArrayList<>(testInteractions);
            interactionsWithUser40.add(singleIn);

            Map<Long, CollaborativeFilteringService.CollaborativeScoreResult> scores =
                    collaborativeFilteringService.computeCollaborativeScoresFromInteractions(
                            40L,
                            List.of(2L, 3L),
                            interactionsWithUser40
                    );

            assertThat(scores).containsKey(2L);
            assertThat(scores).containsKey(3L);
            assertThat(scores.get(2L).score()).isGreaterThanOrEqualTo(0.0);
            assertThat(scores.get(3L).score()).isGreaterThanOrEqualTo(0.0);
        }
    }
}
