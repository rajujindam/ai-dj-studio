package com.aidjstudio.ai_dj_studio;

import com.aidjstudio.ai_dj_studio.dto.track.TrackResponse;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.service.TrackService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrackServiceTest {

    @Mock
    private TrackRepository trackRepository;

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
}
