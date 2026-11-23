package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.track.RetrievedMetadataEntry;
import com.aidjstudio.ai_dj_studio.dto.transition.TransitionDecisionRequest;
import com.aidjstudio.ai_dj_studio.dto.transition.TransitionDecisionResponse;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.service.llm.FakeLlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransitionDecisionServiceTest {

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private TrackMetadataRetrievalService metadataRetrievalService;

    private FakeLlmProvider fakeLlmProvider;
    private CandidateRankingService candidateRankingService;
    private TransitionDecisionService transitionDecisionService;

    @BeforeEach
    void setUp() {
        AcousticMatcherService acousticMatcherService = new AcousticMatcherService();
        candidateRankingService = new CandidateRankingService(acousticMatcherService);
        fakeLlmProvider = new FakeLlmProvider();

        transitionDecisionService = new TransitionDecisionService(
                trackRepository,
                candidateRankingService,
                metadataRetrievalService,
                fakeLlmProvider
        );
    }

    private Track createTrack(Long id, String title, String artist, String bpm, String camelotKey, Double energy) {
        Track track = new Track();
        track.setId(id);
        track.setTitle(title);
        track.setArtist(artist);
        track.setBpm(new BigDecimal(bpm));
        track.setCamelotKey(camelotKey);
        track.setEnergy(BigDecimal.valueOf(energy));
        track.setDurationSeconds(240);
        return track;
    }

    @Test
    @DisplayName("Successfully select top compatible candidate, retrieve metadata, and generate grounded banter")
    void shouldSuccessfullySelectTransitionAndGenerateGroundedScriptWithCitations() {
        Track currentTrack = createTrack(1L, "Midnight City", "M83", "128.00", "8A", 0.8);
        Track candidate1 = createTrack(2L, "Starboy", "The Weeknd", "128.00", "8A", 0.85); // Fully compatible (SAME_KEY, 0 BPM delta)
        Track candidate2 = createTrack(3L, "Strobe", "deadmau5", "128.00", "9A", 0.7); // Compatible (ADJACENT_KEY)
        Track candidate3 = createTrack(4L, "Titanium", "David Guetta", "150.00", "1B", 0.9); // Incompatible (BPM & key clash)

        when(trackRepository.findById(1L)).thenReturn(Mono.just(currentTrack));
        when(trackRepository.findAllById(anyCollection())).thenReturn(Flux.just(candidate1, candidate2, candidate3));

        List<RetrievedMetadataEntry> metadata = List.of(
                RetrievedMetadataEntry.builder()
                        .sourceId("track:1:timeline:101")
                        .trackId(1L)
                        .eventType("DROP")
                        .tsSeconds(64)
                        .text("Massive synth crescendo into the main hook")
                        .build(),
                RetrievedMetadataEntry.builder()
                        .sourceId("track:2:timeline:201")
                        .trackId(2L)
                        .eventType("INTRO")
                        .tsSeconds(0)
                        .text("Pulsing bass groove intro at 128 BPM")
                        .build()
        );

        when(metadataRetrievalService.retrieveForTracks(anyCollection(), any(), any(), any()))
                .thenReturn(Flux.fromIterable(metadata));

        TransitionDecisionRequest request = TransitionDecisionRequest.builder()
                .candidateTrackIds(List.of(2L, 3L, 4L))
                .targetEnergy(BigDecimal.valueOf(0.85))
                .build();

        StepVerifier.create(transitionDecisionService.evaluateTransition(1L, request))
                .assertNext(response -> {
                    assertThat(response.isSeguePossible()).isTrue();
                    assertThat(response.getCurrentTrackId()).isEqualTo(1L);
                    assertThat(response.getSelectedTrackId()).isEqualTo(2L);
                    assertThat(response.getSelectedTrackTitle()).isEqualTo("Starboy");
                    assertThat(response.getBpmDelta()).isEqualByComparingTo("0.00");
                    assertThat(response.getCamelotRelation()).isEqualTo("SAME_KEY");
                    assertThat(response.getSourceIds()).containsExactly("track:1:timeline:101", "track:2:timeline:201");
                    assertThat(response.getScript()).contains("Starboy");
                    assertThat(response.getScript()).contains("track:1:timeline:101");
                    assertThat(response.getScript()).contains("track:2:timeline:201");
                    assertThat(response.getGeneratedAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Return safe explicit no-segue when all candidates fail hard acoustic constraints")
    void shouldReturnSafeExplicitNoSegueWhenNoCandidatesPassHardRules() {
        Track currentTrack = createTrack(1L, "Midnight City", "M83", "128.00", "8A", 0.8);
        Track candidate1 = createTrack(2L, "Fast Track", "Speedy", "160.00", "8A", 0.85); // BPM > 8% clash
        Track candidate2 = createTrack(3L, "Clashing Key", "Clasher", "128.00", "2B", 0.7); // Camelot clash

        when(trackRepository.findById(1L)).thenReturn(Mono.just(currentTrack));
        when(trackRepository.findAllById(anyCollection())).thenReturn(Flux.just(candidate1, candidate2));

        TransitionDecisionRequest request = TransitionDecisionRequest.builder()
                .candidateTrackIds(List.of(2L, 3L))
                .build();

        StepVerifier.create(transitionDecisionService.evaluateTransition(1L, request))
                .assertNext(response -> {
                    assertThat(response.isSeguePossible()).isFalse();
                    assertThat(response.getCurrentTrackId()).isEqualTo(1L);
                    assertThat(response.getSelectedTrackId()).isNull();
                    assertThat(response.getSelectedTrackTitle()).isNull();
                    assertThat(response.getBpmDelta()).isNull();
                    assertThat(response.getCamelotRelation()).isNull();
                    assertThat(response.getRankingExplanation()).contains("No candidate track met harmonic and BPM segue constraints");
                    assertThat(response.getSourceIds()).isEmpty();
                    assertThat(response.getSources()).isEmpty();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Return safe explicit no-segue when candidate IDs list is empty or tracks not found")
    void shouldReturnNoSegueWhenCandidateListIsEmptyOrNoneFound() {
        Track currentTrack = createTrack(1L, "Midnight City", "M83", "128.00", "8A", 0.8);
        when(trackRepository.findById(1L)).thenReturn(Mono.just(currentTrack));

        TransitionDecisionRequest emptyRequest = TransitionDecisionRequest.builder()
                .candidateTrackIds(Collections.emptyList())
                .build();

        StepVerifier.create(transitionDecisionService.evaluateTransition(1L, emptyRequest))
                .assertNext(response -> {
                    assertThat(response.isSeguePossible()).isFalse();
                    assertThat(response.getSelectedTrackId()).isNull();
                    assertThat(response.getRankingExplanation()).contains("No candidate track IDs provided");
                })
                .verifyComplete();

        when(trackRepository.findAllById(anyCollection())).thenReturn(Flux.empty());

        TransitionDecisionRequest notFoundRequest = TransitionDecisionRequest.builder()
                .candidateTrackIds(List.of(99L, 100L))
                .build();

        StepVerifier.create(transitionDecisionService.evaluateTransition(1L, notFoundRequest))
                .assertNext(response -> {
                    assertThat(response.isSeguePossible()).isFalse();
                    assertThat(response.getSelectedTrackId()).isNull();
                    assertThat(response.getRankingExplanation()).contains("None of the provided candidate track IDs exist");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Gracefully fall back to deterministic transition explanation when LLM provider fails")
    void shouldHandleLlmProviderFailureGracefullyWithFallback() {
        Track currentTrack = createTrack(1L, "Midnight City", "M83", "128.00", "8A", 0.8);
        Track candidate = createTrack(2L, "Starboy", "The Weeknd", "128.00", "8A", 0.85);

        when(trackRepository.findById(1L)).thenReturn(Mono.just(currentTrack));
        when(trackRepository.findAllById(anyCollection())).thenReturn(Flux.just(candidate));
        when(metadataRetrievalService.retrieveForTracks(anyCollection(), any(), any(), any()))
                .thenReturn(Flux.empty());

        fakeLlmProvider.setSimulateFailure(true);

        TransitionDecisionRequest request = TransitionDecisionRequest.builder()
                .candidateTrackIds(List.of(2L))
                .build();

        StepVerifier.create(transitionDecisionService.evaluateTransition(1L, request))
                .assertNext(response -> {
                    assertThat(response.isSeguePossible()).isTrue();
                    assertThat(response.getSelectedTrackId()).isEqualTo(2L);
                    // Fallback script still cleanly generated
                    assertThat(response.getScript()).contains("Mixing from \"Midnight City\"");
                    assertThat(response.getScript()).contains("into \"Starboy\"");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Throw IllegalArgumentException when current track is not found")
    void shouldThrowIllegalArgumentExceptionWhenCurrentTrackDoesNotExist() {
        when(trackRepository.findById(999L)).thenReturn(Mono.empty());

        TransitionDecisionRequest request = TransitionDecisionRequest.builder()
                .candidateTrackIds(List.of(2L))
                .build();

        StepVerifier.create(transitionDecisionService.evaluateTransition(999L, request))
                .expectErrorMatches(throwable -> throwable instanceof IllegalArgumentException
                        && throwable.getMessage().contains("Track not found with id: 999"))
                .verify();
    }

    @Test
    @DisplayName("Enforce hard acoustic rules across at least 20 simulated segue decisions")
    void shouldEnforceHardAcousticRulesAcross20SimulatedDecisions() {
        // Track catalog of current tracks and candidate pairs with various BPMs and Camelot keys
        Track currentTrack = createTrack(100L, "Seed Current", "DJ Seed", "128.00", "8A", 0.7);
        when(trackRepository.findById(100L)).thenReturn(Mono.just(currentTrack));

        when(metadataRetrievalService.retrieveForTracks(anyCollection(), any(), any(), any()))
                .thenReturn(Flux.empty());

        record SimulationCase(
                long id,
                String bpm,
                String camelot,
                boolean expectSeguePossible,
                String description
        ) {}

        List<SimulationCase> cases = List.of(
                // 10 Valid Cases
                new SimulationCase(101L, "128.00", "8A", true, "Exact match same key"),
                new SimulationCase(102L, "125.00", "8A", true, "BPM within -2.3%, same key"),
                new SimulationCase(103L, "132.00", "8A", true, "BPM within +3.1%, same key"),
                new SimulationCase(104L, "120.00", "7A", true, "BPM -6.25%, adjacent key (8A -> 7A)"),
                new SimulationCase(105L, "135.00", "9A", true, "BPM +5.47%, adjacent key (8A -> 9A)"),
                new SimulationCase(106L, "128.00", "8B", true, "Same BPM, relative key (8A -> 8B)"),
                new SimulationCase(107L, "130.00", "8B", true, "BPM +1.56%, relative key"),
                new SimulationCase(108L, "64.00", "8A", true, "Half-time direct match (128 -> 64)"),
                new SimulationCase(109L, "66.00", "9A", true, "Half-time within tolerance, adjacent key"),
                new SimulationCase(110L, "256.00", "8A", true, "Double-time direct match (128 -> 256)"),

                // 10 Incompatible Cases
                new SimulationCase(111L, "145.00", "8A", false, "BPM delta +13.3% exceeds 8% tolerance"),
                new SimulationCase(112L, "110.00", "8A", false, "BPM delta -14.1% exceeds 8% tolerance"),
                new SimulationCase(113L, "128.00", "1A", false, "Camelot clash (8A -> 1A, diff 5)"),
                new SimulationCase(114L, "128.00", "3A", false, "Camelot clash (8A -> 3A, diff 5)"),
                new SimulationCase(115L, "128.00", "11B", false, "Camelot clash (8A -> 11B)"),
                new SimulationCase(116L, "128.00", "2B", false, "Camelot clash (8A -> 2B)"),
                new SimulationCase(117L, "160.00", "8B", false, "BPM clash +25% even with relative key"),
                new SimulationCase(118L, "90.00", "8A", false, "BPM clash neither direct nor half/double time"),
                new SimulationCase(119L, "180.00", "8A", false, "BPM clash neither direct nor half/double time"),
                new SimulationCase(120L, "128.00", "6A", false, "Camelot clash (8A -> 6A, diff 2)")
        );

        assertThat(cases).hasSize(20);

        Map<Long, Track> trackMap = new ConcurrentHashMap<>();
        for (SimulationCase sc : cases) {
            trackMap.put(sc.id(), createTrack(sc.id(), "Cand-" + sc.id(), "Artist", sc.bpm(), sc.camelot(), 0.7));
        }

        when(trackRepository.findAllById(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return Flux.fromIterable(ids.stream().map(trackMap::get).filter(t -> t != null).toList());
        });

        int decisionsTested = 0;
        for (SimulationCase sc : cases) {
            TransitionDecisionRequest request = TransitionDecisionRequest.builder()
                    .candidateTrackIds(List.of(sc.id()))
                    .build();

            TransitionDecisionResponse response = transitionDecisionService.evaluateTransition(100L, request).block();
            assertThat(response).isNotNull();

            if (sc.expectSeguePossible()) {
                assertThat(response.isSeguePossible())
                        .as("Case %d: %s should be valid segue", sc.id(), sc.description())
                        .isTrue();
                assertThat(response.getSelectedTrackId()).isEqualTo(sc.id());
                assertThat(response.getBpmDelta()).isNotNull();
                assertThat(response.getCamelotRelation()).isNotNull();
            } else {
                assertThat(response.isSeguePossible())
                        .as("Case %d: %s should be rejected with safe no-segue", sc.id(), sc.description())
                        .isFalse();
                assertThat(response.getSelectedTrackId()).isNull();
            }

            decisionsTested++;
        }

        assertThat(decisionsTested).isEqualTo(20);
    }
}
