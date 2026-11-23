package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.track.RetrievedMetadataEntry;
import com.aidjstudio.ai_dj_studio.dto.track.TrackQnaRequest;
import com.aidjstudio.ai_dj_studio.dto.track.TrackQnaResponse;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.exception.LlmProviderException;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrackQnaServiceTest {

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private TrackMetadataRetrievalService retrievalService;

    private FakeLlmProvider fakeLlmProvider;
    private TrackQnaService trackQnaService;

    private Track testTrack;
    private RetrievedMetadataEntry sampleEntry;
    private RetrievedMetadataEntry triviaEntry;

    @BeforeEach
    void setUp() {
        fakeLlmProvider = new FakeLlmProvider();
        trackQnaService = new TrackQnaService(trackRepository, retrievalService, fakeLlmProvider);

        testTrack = new Track(
                1L,
                "Night Drive",
                "Nova Circuit",
                240,
                "https://cdn.example.com/audio/1.mp3",
                new BigDecimal("124.00"),
                "8A",
                new BigDecimal("0.780"),
                new BigDecimal("0.650")
        );

        sampleEntry = RetrievedMetadataEntry.builder()
                .id(101L)
                .trackId(1L)
                .sourceId("track:1:timeline:101")
                .tsSeconds(15)
                .eventType("sample_credit")
                .text("Vocal chop sampled from a 1981 soul record.")
                .score(7.0)
                .build();

        triviaEntry = RetrievedMetadataEntry.builder()
                .id(102L)
                .trackId(1L)
                .sourceId("track:1:timeline:102")
                .tsSeconds(45)
                .eventType("trivia")
                .text("Recorded in an analog studio in Berlin.")
                .score(5.0)
                .build();
    }

    @Test
    @DisplayName("Should return grounded answer with citations and source IDs when metadata evidence exists")
    void answerTrackQuestion_whenEvidenceFound_shouldReturnGroundedAnswerWithSourceIdsAndCitations() {
        when(trackRepository.findById(1L)).thenReturn(Mono.just(testTrack));
        when(retrievalService.retrieve(eq(1L), eq("What is the sample?"), isNull(), anyInt()))
                .thenReturn(Flux.just(sampleEntry));

        TrackQnaRequest request = new TrackQnaRequest("What is the sample?");

        StepVerifier.create(trackQnaService.answerTrackQuestion(1L, request))
                .assertNext(response -> {
                    assertTrue(response.isGrounded());
                    assertNotNull(response.getAnswer());
                    assertTrue(response.getAnswer().contains("Nova Circuit"));
                    assertTrue(response.getAnswer().contains("[track:1:timeline:101]"));
                    assertTrue(response.getAnswer().contains("1981 soul record"));

                    assertEquals(1, response.getSourceIds().size());
                    assertEquals("track:1:timeline:101", response.getSourceIds().get(0));

                    assertEquals(1, response.getSources().size());
                    assertEquals(101L, response.getSources().get(0).getId());
                })
                .verifyComplete();

        verify(trackRepository).findById(1L);
        verify(retrievalService).retrieve(eq(1L), eq("What is the sample?"), isNull(), anyInt());
    }

    @Test
    @DisplayName("Should return explicit cannot-answer response without calling LLM provider when no evidence exists")
    void answerTrackQuestion_whenNoEvidenceFound_shouldReturnCannotAnswerWithoutCallingProvider() {
        when(trackRepository.findById(1L)).thenReturn(Mono.just(testTrack));
        when(retrievalService.retrieve(eq(1L), eq("Who played the drums?"), isNull(), anyInt()))
                .thenReturn(Flux.empty());

        TrackQnaRequest request = new TrackQnaRequest("Who played the drums?");

        StepVerifier.create(trackQnaService.answerTrackQuestion(1L, request))
                .assertNext(response -> {
                    assertFalse(response.isGrounded());
                    assertEquals(TrackQnaService.CANNOT_ANSWER_MESSAGE, response.getAnswer());
                    assertTrue(response.getSourceIds().isEmpty());
                    assertTrue(response.getSources().isEmpty());
                })
                .verifyComplete();

        verify(trackRepository).findById(1L);
        verify(retrievalService).retrieve(eq(1L), eq("Who played the drums?"), isNull(), anyInt());
    }

    @Test
    @DisplayName("Should propagate LlmProviderException when upstream provider fails")
    void answerTrackQuestion_whenProviderFails_shouldPropagateLlmProviderException() {
        fakeLlmProvider.setSimulateFailure(true);

        when(trackRepository.findById(1L)).thenReturn(Mono.just(testTrack));
        when(retrievalService.retrieve(eq(1L), eq("What is the sample?"), isNull(), anyInt()))
                .thenReturn(Flux.just(sampleEntry));

        TrackQnaRequest request = new TrackQnaRequest("What is the sample?");

        StepVerifier.create(trackQnaService.answerTrackQuestion(1L, request))
                .expectErrorMatches(throwable ->
                        throwable instanceof LlmProviderException
                                && throwable.getMessage().contains("Simulated upstream LLM provider failure")
                )
                .verify();

        verify(trackRepository).findById(1L);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when track does not exist")
    void answerTrackQuestion_whenTrackNotFound_shouldThrowIllegalArgumentException() {
        when(trackRepository.findById(999L)).thenReturn(Mono.empty());

        TrackQnaRequest request = new TrackQnaRequest("What is the sample?");

        StepVerifier.create(trackQnaService.answerTrackQuestion(999L, request))
                .expectErrorMatches(throwable ->
                        throwable instanceof IllegalArgumentException
                                && throwable.getMessage().contains("Track not found with id: 999")
                )
                .verify();

        verify(trackRepository).findById(999L);
        verifyNoInteractions(retrievalService);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when question is blank or null")
    void answerTrackQuestion_whenQuestionIsBlankOrNull_shouldThrowIllegalArgumentException() {
        StepVerifier.create(trackQnaService.answerTrackQuestion(1L, new TrackQnaRequest("   ")))
                .expectErrorMatches(t -> t instanceof IllegalArgumentException && t.getMessage().contains("blank"))
                .verify();

        StepVerifier.create(trackQnaService.answerTrackQuestion(1L, new TrackQnaRequest(null)))
                .expectErrorMatches(t -> t instanceof IllegalArgumentException && t.getMessage().contains("blank"))
                .verify();

        StepVerifier.create(trackQnaService.answerTrackQuestion(1L, null))
                .expectErrorMatches(t -> t instanceof IllegalArgumentException && t.getMessage().contains("blank"))
                .verify();

        verifyNoInteractions(trackRepository);
        verifyNoInteractions(retrievalService);
    }

    @Test
    @DisplayName("Should collect and order all source IDs when multiple evidence entries are cited")
    void answerTrackQuestion_whenMultipleEvidenceEntriesFound_shouldIncludeAllSourceIdsInOrder() {
        when(trackRepository.findById(1L)).thenReturn(Mono.just(testTrack));
        when(retrievalService.retrieve(eq(1L), eq("Tell me about the production and sample"), isNull(), anyInt()))
                .thenReturn(Flux.just(sampleEntry, triviaEntry));

        TrackQnaRequest request = new TrackQnaRequest("Tell me about the production and sample");

        StepVerifier.create(trackQnaService.answerTrackQuestion(1L, request))
                .assertNext(response -> {
                    assertTrue(response.isGrounded());
                    assertEquals(2, response.getSourceIds().size());
                    assertEquals("track:1:timeline:101", response.getSourceIds().get(0));
                    assertEquals("track:1:timeline:102", response.getSourceIds().get(1));
                    assertEquals(2, response.getSources().size());
                })
                .verifyComplete();
    }
}
