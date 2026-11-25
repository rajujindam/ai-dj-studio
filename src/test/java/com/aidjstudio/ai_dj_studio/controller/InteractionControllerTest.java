package com.aidjstudio.ai_dj_studio.controller;

import com.aidjstudio.ai_dj_studio.dto.interaction.InteractionResponse;
import com.aidjstudio.ai_dj_studio.dto.interaction.RecordInteractionRequest;
import com.aidjstudio.ai_dj_studio.dto.interaction.TrackLikeRequest;
import com.aidjstudio.ai_dj_studio.service.InteractionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InteractionControllerTest {

    @Mock
    private InteractionService interactionService;

    private InteractionController controller;

    private Jwt testJwt;

    @BeforeEach
    void setUp() {
        controller = new InteractionController(interactionService);

        testJwt = mock(Jwt.class);
        lenient().when(testJwt.getSubject()).thenReturn("100");
    }

    @Test
    @DisplayName("POST /api/v1/interactions should record interaction and return 201 CREATED")
    void shouldRecordInteraction() {
        RecordInteractionRequest request = RecordInteractionRequest.builder()
                .trackId(10L)
                .playedSeconds(120)
                .completed(false)
                .liked(true)
                .build();

        InteractionResponse responseDto = InteractionResponse.builder()
                .id(1L)
                .userId(100L)
                .trackId(10L)
                .trackTitle("Midnight City")
                .playedSeconds(120)
                .completed(false)
                .liked(true)
                .createdAt(LocalDateTime.now())
                .build();

        when(interactionService.recordInteraction(any(RecordInteractionRequest.class), eq(100L)))
                .thenReturn(Mono.just(responseDto));

        StepVerifier.create(controller.recordInteraction(request, testJwt))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                    assertThat(response.getBody()).isNotNull();
                    assertThat(response.getBody().getUserId()).isEqualTo(100L);
                    assertThat(response.getBody().getTrackId()).isEqualTo(10L);
                    assertThat(response.getBody().getLiked()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("POST /api/v1/interactions/tracks/{id}/like should toggle track like and return 200 OK")
    void shouldToggleLike() {
        TrackLikeRequest request = TrackLikeRequest.builder()
                .liked(true)
                .loungeId(5L)
                .build();

        InteractionResponse responseDto = InteractionResponse.builder()
                .id(2L)
                .userId(100L)
                .trackId(10L)
                .liked(true)
                .loungeId(5L)
                .build();

        when(interactionService.toggleLike(eq(10L), eq(true), eq(5L), eq(100L)))
                .thenReturn(Mono.just(responseDto));

        StepVerifier.create(controller.likeTrack(10L, request, testJwt))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(response.getBody()).isNotNull();
                    assertThat(response.getBody().getLiked()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("POST /api/v1/interactions/lounges/{id}/co-listening should record co-listening for lounge")
    void shouldRecordCoListening() {
        InteractionResponse r1 = InteractionResponse.builder()
                .id(1L)
                .userId(100L)
                .trackId(10L)
                .loungeId(5L)
                .playedSeconds(200)
                .completed(true)
                .build();

        when(interactionService.recordCoListening(eq(5L), eq(10L), eq(200), eq(true)))
                .thenReturn(Flux.just(r1));

        StepVerifier.create(controller.recordLoungeCoListening(5L, 10L, 200, true))
                .assertNext(res -> {
                    assertThat(res.getUserId()).isEqualTo(100L);
                    assertThat(res.getTrackId()).isEqualTo(10L);
                    assertThat(res.getLoungeId()).isEqualTo(5L);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("GET /api/v1/interactions/me should return authenticated user's interactions")
    void shouldGetMyInteractions() {
        InteractionResponse r1 = InteractionResponse.builder()
                .id(1L)
                .userId(100L)
                .trackId(10L)
                .build();

        when(interactionService.getUserInteractions(100L))
                .thenReturn(Flux.just(r1));

        StepVerifier.create(controller.getMyInteractions(testJwt))
                .assertNext(res -> {
                    assertThat(res.getUserId()).isEqualTo(100L);
                    assertThat(res.getTrackId()).isEqualTo(10L);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("GET /api/v1/interactions/lounges/{id} should return lounge interactions")
    void shouldGetLoungeInteractions() {
        InteractionResponse r1 = InteractionResponse.builder()
                .id(1L)
                .userId(100L)
                .loungeId(5L)
                .build();

        when(interactionService.getLoungeInteractions(5L))
                .thenReturn(Flux.just(r1));

        StepVerifier.create(controller.getLoungeInteractions(5L))
                .assertNext(res -> {
                    assertThat(res.getLoungeId()).isEqualTo(5L);
                })
                .verifyComplete();
    }
}
