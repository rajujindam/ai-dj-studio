package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.track.RetrievedMetadataEntry;
import com.aidjstudio.ai_dj_studio.entity.TrackTimelineEvent;
import com.aidjstudio.ai_dj_studio.repository.TrackTimelineEventRepository;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrackMetadataRetrievalServiceTest {

    @Mock
    private TrackTimelineEventRepository timelineEventRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private TrackMetadataRetrievalService retrievalService;

    private TrackTimelineEvent sampleEvent;
    private TrackTimelineEvent triviaEvent;
    private TrackTimelineEvent productionEvent;

    @BeforeEach
    void setUp() {
        sampleEvent = TrackTimelineEvent.builder()
                .id(101L)
                .trackId(1L)
                .tsSeconds(15)
                .eventType("sample_credit")
                .payload(Json.of("{\"text\":\"Vocal chop sampled from a 1981 soul record.\"}"))
                .build();

        triviaEvent = TrackTimelineEvent.builder()
                .id(102L)
                .trackId(1L)
                .tsSeconds(45)
                .eventType("trivia")
                .payload(Json.of("{\"text\":\"Produced during an all-night session in Berlin with vintage synthesizers.\"}"))
                .build();

        productionEvent = TrackTimelineEvent.builder()
                .id(103L)
                .trackId(1L)
                .tsSeconds(90)
                .eventType("production_note")
                .payload(Json.of("{\"text\":\"Sub-bass drop at chorus engineered with analog tape compression.\"}"))
                .build();
    }

    @Test
    @DisplayName("Should return relevant entries ranked by relevance score when query keywords match")
    void retrieve_whenQueryMatchesKeywords_shouldReturnRelevantEntriesRankedByScore() {
        when(timelineEventRepository.findByTrackIdOrderByTsSecondsAsc(1L))
                .thenReturn(Flux.just(sampleEvent, triviaEvent, productionEvent));

        // "soul record" should match sampleEvent with high score
        StepVerifier.create(retrievalService.retrieve(1L, "soul record"))
                .assertNext(entry -> {
                    assertEquals(101L, entry.getId());
                    assertEquals(1L, entry.getTrackId());
                    assertEquals("track:1:timeline:101", entry.getSourceId());
                    assertEquals("sample_credit", entry.getEventType());
                    assertEquals(15, entry.getTsSeconds());
                    assertTrue(entry.getText().contains("1981 soul record"));
                    assertTrue(entry.getScore() > 0.0);
                })
                .verifyComplete();

        verify(timelineEventRepository).findByTrackIdOrderByTsSecondsAsc(1L);
    }

    @Test
    @DisplayName("Should return empty Flux when no entries match the query")
    void retrieve_whenNoMatches_shouldReturnEmptyFlux() {
        when(timelineEventRepository.findByTrackIdOrderByTsSecondsAsc(1L))
                .thenReturn(Flux.just(sampleEvent, triviaEvent, productionEvent));

        StepVerifier.create(retrievalService.retrieve(1L, "orchestral harp acoustic guitar"))
                .verifyComplete();

        verify(timelineEventRepository).findByTrackIdOrderByTsSecondsAsc(1L);
    }

    @Test
    @DisplayName("Should generate stable, deterministic source IDs format 'track:{trackId}:timeline:{eventId}'")
    void retrieve_shouldGenerateStableAndDeterministicSourceIds() {
        when(timelineEventRepository.findByTrackIdOrderByTsSecondsAsc(1L))
                .thenReturn(Flux.just(triviaEvent));

        StepVerifier.create(retrievalService.retrieve(1L, "Berlin"))
                .assertNext(entry -> {
                    assertEquals("track:1:timeline:102", entry.getSourceId());
                })
                .verifyComplete();

        // Also test static helper directly
        assertEquals("track:42:timeline:999", TrackMetadataRetrievalService.buildStableSourceId(42L, 999L));
    }

    @Test
    @DisplayName("Should strictly isolate tracks so queries for track 1 never return track 2 metadata")
    void retrieve_trackIsolation_shouldNeverReturnEventsFromOtherTracks() {
        when(timelineEventRepository.findByTrackIdOrderByTsSecondsAsc(1L))
                .thenReturn(Flux.just(sampleEvent));

        // When retrieving for track 1, only track 1 is queried from repository
        StepVerifier.create(retrievalService.retrieve(1L, "soul"))
                .assertNext(entry -> {
                    assertEquals(1L, entry.getTrackId());
                    assertEquals("track:1:timeline:101", entry.getSourceId());
                })
                .verifyComplete();

        verify(timelineEventRepository, times(1)).findByTrackIdOrderByTsSecondsAsc(1L);
        verify(timelineEventRepository, never()).findByTrackIdOrderByTsSecondsAsc(2L);
    }

    @Test
    @DisplayName("Should filter by event type when eventType parameter is specified")
    void retrieve_withEventTypeFilter_shouldOnlyReturnMatchingEventType() {
        when(timelineEventRepository.findByTrackIdOrderByTsSecondsAsc(1L))
                .thenReturn(Flux.just(sampleEvent, triviaEvent, productionEvent));

        StepVerifier.create(retrievalService.retrieve(1L, null, "trivia", 10))
                .assertNext(entry -> {
                    assertEquals("trivia", entry.getEventType());
                    assertEquals(102L, entry.getId());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return all track events in chronological order when query is null or blank")
    void retrieve_whenQueryIsBlankOrNull_shouldReturnAllEntriesInChronologicalOrder() {
        when(timelineEventRepository.findByTrackIdOrderByTsSecondsAsc(1L))
                .thenReturn(Flux.just(sampleEvent, triviaEvent, productionEvent));

        StepVerifier.create(retrievalService.retrieve(1L, "   "))
                .assertNext(entry -> assertEquals(15, entry.getTsSeconds()))
                .assertNext(entry -> assertEquals(45, entry.getTsSeconds()))
                .assertNext(entry -> assertEquals(90, entry.getTsSeconds()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should support multi-track retrieval across recent lounge tracks with track isolation")
    void retrieveForTracks_shouldIsolateAndIncludeOnlyRequestedTracksWithCorrectSourceIds() {
        TrackTimelineEvent track2Event = TrackTimelineEvent.builder()
                .id(201L)
                .trackId(2L)
                .tsSeconds(30)
                .eventType("trivia")
                .payload(Json.of("{\"text\":\"Remixed by Japanese ambient artist in Tokyo.\"}"))
                .build();

        when(timelineEventRepository.findByTrackIdInOrderByTsSecondsAsc(List.of(1L, 2L)))
                .thenReturn(Flux.just(sampleEvent, track2Event));

        StepVerifier.create(retrievalService.retrieveForTracks(List.of(1L, 2L), "Tokyo", null, 10))
                .assertNext(entry -> {
                    assertEquals(2L, entry.getTrackId());
                    assertEquals(201L, entry.getId());
                    assertEquals("track:2:timeline:201", entry.getSourceId());
                    assertTrue(entry.getText().contains("Tokyo"));
                })
                .verifyComplete();

        verify(timelineEventRepository).findByTrackIdInOrderByTsSecondsAsc(List.of(1L, 2L));
    }

    @Test
    @DisplayName("Should return empty Flux when trackId is null or trackIds collection is empty")
    void retrieve_nullInput_shouldReturnEmptyFlux() {
        StepVerifier.create(retrievalService.retrieve(null, "anything"))
                .verifyComplete();

        StepVerifier.create(retrievalService.retrieveForTracks(null, "anything", null, 10))
                .verifyComplete();

        StepVerifier.create(retrievalService.retrieveForTracks(List.of(), "anything", null, 10))
                .verifyComplete();

        verifyNoInteractions(timelineEventRepository);
    }
}
