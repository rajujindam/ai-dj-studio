package com.aidjstudio.ai_dj_studio;

import com.aidjstudio.ai_dj_studio.dto.track.TrackResponse;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.entity.TrackTimelineEvent;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackTimelineEventRepository;
import com.aidjstudio.ai_dj_studio.service.TrackService;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrackServiceTest {

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private TrackTimelineEventRepository timelineEventRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private TrackService trackService;

    private Track createTrack(Long id, String title, String artist) {
        return new Track(
                id,
                title,
                artist,
                240,
                "https://example.com/audio/" + id + ".mp3",
                new BigDecimal("124.00"),
                "8A",
                new BigDecimal("0.780"),
                new BigDecimal("0.650")
        );
    }

    @Test
    void getAllTracks_shouldReturnAllTracksAsDtos() {
        Track track1 = createTrack(1L, "Song One", "Artist One");
        Track track2 = createTrack(2L, "Song Two", "Artist Two");

        when(trackRepository.findAll()).thenReturn(Flux.just(track1, track2));

        StepVerifier.create(trackService.getAllTracks())
                .assertNext(dto -> {
                    assertEquals(1L, dto.getId());
                    assertEquals("Song One", dto.getTitle());
                    assertEquals("Artist One", dto.getArtist());
                    assertEquals(240, dto.getDurationSeconds());
                    assertEquals("https://example.com/audio/1.mp3", dto.getAudioAssetUrl());
                    assertEquals(new BigDecimal("124.00"), dto.getBpm());
                    assertEquals("8A", dto.getCamelotKey());
                    assertEquals(new BigDecimal("0.780"), dto.getEnergy());
                    assertEquals(new BigDecimal("0.650"), dto.getValence());
                })
                .assertNext(dto -> {
                    assertEquals(2L, dto.getId());
                    assertEquals("Song Two", dto.getTitle());
                    assertEquals("Artist Two", dto.getArtist());
                })
                .verifyComplete();

        verify(trackRepository).findAll();
    }

    @Test
    void getTrackById_whenTrackExists_shouldReturnTrackDto() {
        Track track = createTrack(10L, "Specific Song", "Specific Artist");

        when(trackRepository.findById(10L)).thenReturn(Mono.just(track));

        StepVerifier.create(trackService.getTrackById(10L))
                .assertNext(dto -> {
                    assertEquals(10L, dto.getId());
                    assertEquals("Specific Song", dto.getTitle());
                    assertEquals("Specific Artist", dto.getArtist());
                    assertEquals("8A", dto.getCamelotKey());
                })
                .verifyComplete();

        verify(trackRepository).findById(10L);
    }

    @Test
    void getTrackById_whenTrackDoesNotExist_shouldThrowIllegalArgumentException() {
        when(trackRepository.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(trackService.getTrackById(999L))
                .expectErrorMatches(throwable ->
                        throwable instanceof IllegalArgumentException
                                && throwable.getMessage().contains("Track not found with id: 999")
                )
                .verify();

        verify(trackRepository).findById(999L);
    }

    @Test
    void getAcousticProfileByTrackId_whenTrackExistsWithTimeline_shouldReturnProfileAndOrderedTimeline() {
        Track track = createTrack(10L, "Night Drive", "Nova Circuit");
        track.setCamelotKey("8a"); // lowercase to test normalization

        TrackTimelineEvent event1 = TrackTimelineEvent.builder()
                .id(1L)
                .trackId(10L)
                .tsSeconds(12)
                .eventType("sample_credit")
                .payload(Json.of("{\"text\":\"Vocal chop sampled from a 1981 soul record.\"}"))
                .build();

        TrackTimelineEvent event2 = TrackTimelineEvent.builder()
                .id(2L)
                .trackId(10L)
                .tsSeconds(90)
                .eventType("trivia")
                .payload(Json.of("{\"text\":\"Produced during an all-night session in Berlin.\"}"))
                .build();

        when(trackRepository.findById(10L)).thenReturn(Mono.just(track));
        when(timelineEventRepository.findByTrackIdOrderByTsSecondsAsc(10L))
                .thenReturn(Flux.just(event1, event2));

        StepVerifier.create(trackService.getAcousticProfileByTrackId(10L))
                .assertNext(response -> {
                    assertEquals(10L, response.getTrackId());
                    assertEquals("Night Drive", response.getTitle());
                    assertEquals("Nova Circuit", response.getArtist());
                    assertNotNull(response.getAcoustic());
                    assertEquals(new BigDecimal("124.00"), response.getAcoustic().getBpm());
                    assertEquals("8A", response.getAcoustic().getCamelotKey());
                    assertEquals(new BigDecimal("0.780"), response.getAcoustic().getEnergy());
                    assertEquals(new BigDecimal("0.650"), response.getAcoustic().getValence());

                    assertNotNull(response.getTimeline());
                    assertEquals(2, response.getTimeline().size());
                    assertEquals(1L, response.getTimeline().get(0).getId());
                    assertEquals(12, response.getTimeline().get(0).getTs());
                    assertEquals("sample_credit", response.getTimeline().get(0).getType());
                    assertEquals("Vocal chop sampled from a 1981 soul record.", response.getTimeline().get(0).getText());

                    assertEquals(2L, response.getTimeline().get(1).getId());
                    assertEquals(90, response.getTimeline().get(1).getTs());
                    assertEquals("trivia", response.getTimeline().get(1).getType());
                    assertEquals("Produced during an all-night session in Berlin.", response.getTimeline().get(1).getText());
                })
                .verifyComplete();

        verify(trackRepository).findById(10L);
        verify(timelineEventRepository).findByTrackIdOrderByTsSecondsAsc(10L);
    }

    @Test
    void getAcousticProfileByTrackId_whenTrackNotFound_shouldThrowIllegalArgumentException() {
        when(trackRepository.findById(404L)).thenReturn(Mono.empty());

        StepVerifier.create(trackService.getAcousticProfileByTrackId(404L))
                .expectErrorMatches(throwable ->
                        throwable instanceof IllegalArgumentException
                                && throwable.getMessage().contains("Track not found with id: 404")
                )
                .verify();

        verify(trackRepository).findById(404L);
    }

    @Test
    void getAcousticProfileByTrackId_whenCamelotKeyIsInvalid_shouldThrowIllegalArgumentException() {
        Track track = createTrack(20L, "Bad Key Track", "Bad Artist");
        track.setCamelotKey("13A");

        when(trackRepository.findById(20L)).thenReturn(Mono.just(track));

        StepVerifier.create(trackService.getAcousticProfileByTrackId(20L))
                .expectErrorMatches(throwable ->
                        throwable instanceof IllegalArgumentException
                                && throwable.getMessage().contains("Track acoustic profile not found or incomplete")
                )
                .verify();
    }

    @Test
    void getAcousticProfileByTrackId_whenBpmIsMissingOrNonPositive_shouldThrowIllegalArgumentException() {
        Track track = createTrack(21L, "Zero BPM Track", "Bad Artist");
        track.setBpm(BigDecimal.ZERO);

        when(trackRepository.findById(21L)).thenReturn(Mono.just(track));

        StepVerifier.create(trackService.getAcousticProfileByTrackId(21L))
                .expectErrorMatches(throwable ->
                        throwable instanceof IllegalArgumentException
                                && throwable.getMessage().contains("Track acoustic profile not found or incomplete")
                )
                .verify();
    }

    @Test
    void getAcousticProfileByTrackId_whenEnergyIsOutOfRange_shouldThrowIllegalArgumentException() {
        Track track = createTrack(22L, "High Energy Track", "Bad Artist");
        track.setEnergy(new BigDecimal("1.50"));

        when(trackRepository.findById(22L)).thenReturn(Mono.just(track));

        StepVerifier.create(trackService.getAcousticProfileByTrackId(22L))
                .expectErrorMatches(throwable ->
                        throwable instanceof IllegalArgumentException
                                && throwable.getMessage().contains("Track acoustic profile not found or incomplete")
                )
                .verify();
    }

    @Test
    void getAcousticProfileByTrackId_whenValenceIsNegative_shouldThrowIllegalArgumentException() {
        Track track = createTrack(23L, "Negative Valence Track", "Bad Artist");
        track.setValence(new BigDecimal("-0.10"));

        when(trackRepository.findById(23L)).thenReturn(Mono.just(track));

        StepVerifier.create(trackService.getAcousticProfileByTrackId(23L))
                .expectErrorMatches(throwable ->
                        throwable instanceof IllegalArgumentException
                                && throwable.getMessage().contains("Track acoustic profile not found or incomplete")
                )
                .verify();
    }
}
