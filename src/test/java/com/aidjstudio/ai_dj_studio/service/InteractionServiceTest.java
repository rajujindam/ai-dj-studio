package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.interaction.InteractionResponse;
import com.aidjstudio.ai_dj_studio.dto.interaction.RecordInteractionRequest;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.Interaction;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipantRole;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.InteractionRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.websocket.LoungeSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InteractionServiceTest {

    @Mock
    private InteractionRepository interactionRepository;

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private DjLoungeRepository djLoungeRepository;

    @Mock
    private LoungeParticipantRepository loungeParticipantRepository;

    @Mock
    private LoungeSessionManager loungeSessionManager;

    private InteractionService interactionService;

    private Track testTrack;
    private DjLounge testLounge;

    @BeforeEach
    void setUp() {
        interactionService = new InteractionService(
                interactionRepository,
                trackRepository,
                djLoungeRepository,
                loungeParticipantRepository,
                loungeSessionManager
        );

        testTrack = new Track();
        testTrack.setId(10L);
        testTrack.setTitle("Midnight City");
        testTrack.setArtist("M83");
        testTrack.setDurationSeconds(200); // 200 seconds

        testLounge = new DjLounge();
        testLounge.setId(5L);
        testLounge.setHostUserId(100L);
        testLounge.setStatus(LoungeStatus.LIVE);
    }

    @Nested
    @DisplayName("Interaction Recording & Boundary Tests")
    class RecordInteractionTests {

        @Test
        @DisplayName("Successfully record normal listening interaction with valid boundary data")
        void shouldRecordValidListeningInteraction() {
            when(trackRepository.findById(10L)).thenReturn(Mono.just(testTrack));
            when(interactionRepository.findTopByUserIdAndTrackIdOrderByCreatedAtDesc(1L, 10L))
                    .thenReturn(Mono.empty());

            when(interactionRepository.save(any(Interaction.class))).thenAnswer(inv -> {
                Interaction entity = inv.getArgument(0);
                entity.setId(501L);
                return Mono.just(entity);
            });

            RecordInteractionRequest request = RecordInteractionRequest.builder()
                    .trackId(10L)
                    .playedSeconds(120)
                    .completed(false)
                    .liked(false)
                    .build();

            StepVerifier.create(interactionService.recordInteraction(request, 1L))
                    .assertNext(response -> {
                        assertThat(response.getId()).isEqualTo(501L);
                        assertThat(response.getUserId()).isEqualTo(1L);
                        assertThat(response.getTrackId()).isEqualTo(10L);
                        assertThat(response.getTrackTitle()).isEqualTo("Midnight City");
                        assertThat(response.getPlayedSeconds()).isEqualTo(120);
                        assertThat(response.getCompleted()).isFalse();
                        assertThat(response.getLiked()).isFalse();
                    })
                    .verifyComplete();

            verify(interactionRepository, times(1)).save(any(Interaction.class));
        }

        @Test
        @DisplayName("Cap played seconds at actual track duration to prevent fabricated stats")
        void shouldCapPlayedSecondsAtTrackDuration() {
            when(trackRepository.findById(10L)).thenReturn(Mono.just(testTrack)); // duration 200s
            when(interactionRepository.findTopByUserIdAndTrackIdOrderByCreatedAtDesc(1L, 10L))
                    .thenReturn(Mono.empty());

            when(interactionRepository.save(any(Interaction.class))).thenAnswer(inv -> {
                Interaction entity = inv.getArgument(0);
                entity.setId(502L);
                return Mono.just(entity);
            });

            RecordInteractionRequest request = RecordInteractionRequest.builder()
                    .trackId(10L)
                    .playedSeconds(9999) // Client claims 9999 seconds on a 200s song
                    .build();

            StepVerifier.create(interactionService.recordInteraction(request, 1L))
                    .assertNext(response -> {
                        assertThat(response.getPlayedSeconds()).isEqualTo(200); // Capped to 200
                        assertThat(response.getCompleted()).isTrue(); // Automatically completed (200 >= 85% of 200)
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Automatically mark completed = true when played seconds >= 85% of track duration")
        void shouldAutoCompleteWhenPlayedSecondsExceeds85Percent() {
            when(trackRepository.findById(10L)).thenReturn(Mono.just(testTrack)); // duration 200s, 85% = 170s
            when(interactionRepository.findTopByUserIdAndTrackIdOrderByCreatedAtDesc(1L, 10L))
                    .thenReturn(Mono.empty());

            when(interactionRepository.save(any(Interaction.class))).thenAnswer(inv -> {
                Interaction entity = inv.getArgument(0);
                entity.setId(503L);
                return Mono.just(entity);
            });

            RecordInteractionRequest request = RecordInteractionRequest.builder()
                    .trackId(10L)
                    .playedSeconds(175) // 175s >= 170s
                    .completed(false) // Client didn't set completed
                    .build();

            StepVerifier.create(interactionService.recordInteraction(request, 1L))
                    .assertNext(response -> {
                        assertThat(response.getCompleted()).isTrue();
                        assertThat(response.getPlayedSeconds()).isEqualTo(175);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Anti-fabrication: Disallow completed = true when played seconds is negligible (<15s)")
        void shouldDisallowCompletedWhenPlayedSecondsIsNegligible() {
            when(trackRepository.findById(10L)).thenReturn(Mono.just(testTrack));
            when(interactionRepository.findTopByUserIdAndTrackIdOrderByCreatedAtDesc(1L, 10L))
                    .thenReturn(Mono.empty());

            when(interactionRepository.save(any(Interaction.class))).thenAnswer(inv -> {
                Interaction entity = inv.getArgument(0);
                entity.setId(504L);
                return Mono.just(entity);
            });

            RecordInteractionRequest request = RecordInteractionRequest.builder()
                    .trackId(10L)
                    .playedSeconds(8) // Only 8 seconds played
                    .completed(true) // Client claims completed
                    .build();

            StepVerifier.create(interactionService.recordInteraction(request, 1L))
                    .assertNext(response -> {
                        assertThat(response.getCompleted()).isFalse(); // Rejected fabrication
                        assertThat(response.getPlayedSeconds()).isEqualTo(8);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Ignore sub-5s tick noise when track is neither liked nor completed (sensible event boundary)")
        void shouldIgnoreSub5SecondTickNoise() {
            when(trackRepository.findById(10L)).thenReturn(Mono.just(testTrack));

            RecordInteractionRequest request = RecordInteractionRequest.builder()
                    .trackId(10L)
                    .playedSeconds(2) // 2 second tick noise
                    .completed(false)
                    .liked(false)
                    .build();

            StepVerifier.create(interactionService.recordInteraction(request, 1L))
                    .assertNext(response -> {
                        assertThat(response.getId()).isNull(); // Not saved to DB
                        assertThat(response.getPlayedSeconds()).isEqualTo(2);
                    })
                    .verifyComplete();

            verify(interactionRepository, never()).save(any());
        }

        @Test
        @DisplayName("Consolidate progress into recent record when repeated ticks arrive within debounce window")
        void shouldDebounceAndConsolidateRepeatedTicksWithin10Seconds() {
            when(trackRepository.findById(10L)).thenReturn(Mono.just(testTrack));

            Interaction recent = Interaction.builder()
                    .id(1001L)
                    .userId(1L)
                    .trackId(10L)
                    .playedSeconds(30)
                    .completed(false)
                    .liked(false)
                    .createdAt(LocalDateTime.now().minusSeconds(3)) // Created 3 seconds ago (within 10s debounce)
                    .build();

            when(interactionRepository.findTopByUserIdAndTrackIdOrderByCreatedAtDesc(1L, 10L))
                    .thenReturn(Mono.just(recent));

            when(interactionRepository.save(any(Interaction.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            RecordInteractionRequest updateTick = RecordInteractionRequest.builder()
                    .trackId(10L)
                    .playedSeconds(35)
                    .build();

            StepVerifier.create(interactionService.recordInteraction(updateTick, 1L))
                    .assertNext(response -> {
                        assertThat(response.getId()).isEqualTo(1001L); // Updated existing record
                        assertThat(response.getPlayedSeconds()).isEqualTo(35);
                    })
                    .verifyComplete();

            verify(interactionRepository, times(1)).save(recent);
        }

        @Test
        @DisplayName("Throw IllegalArgumentException when track does not exist")
        void shouldThrowWhenTrackNotFound() {
            when(trackRepository.findById(999L)).thenReturn(Mono.empty());

            RecordInteractionRequest request = RecordInteractionRequest.builder()
                    .trackId(999L)
                    .playedSeconds(30)
                    .build();

            StepVerifier.create(interactionService.recordInteraction(request, 1L))
                    .expectErrorMatches(ex -> ex instanceof IllegalArgumentException
                            && ex.getMessage().contains("Track not found with id: 999"))
                    .verify();
        }

        @Test
        @DisplayName("Throw IllegalArgumentException when lounge does not exist")
        void shouldThrowWhenLoungeNotFound() {
            when(trackRepository.findById(10L)).thenReturn(Mono.just(testTrack));
            when(djLoungeRepository.findById(888L)).thenReturn(Mono.empty());

            RecordInteractionRequest request = RecordInteractionRequest.builder()
                    .trackId(10L)
                    .loungeId(888L)
                    .playedSeconds(30)
                    .build();

            StepVerifier.create(interactionService.recordInteraction(request, 1L))
                    .expectErrorMatches(ex -> ex instanceof IllegalArgumentException
                            && ex.getMessage().contains("Lounge not found with id: 888"))
                    .verify();
        }

        @Test
        @DisplayName("Throw AccessDeniedException when authenticated user is null")
        void shouldThrowWhenUserNotAuthenticated() {
            RecordInteractionRequest request = RecordInteractionRequest.builder()
                    .trackId(10L)
                    .build();

            StepVerifier.create(interactionService.recordInteraction(request, null))
                    .expectErrorMatches(ex -> ex instanceof AccessDeniedException)
                    .verify();
        }
    }

    @Nested
    @DisplayName("Track Like Tests")
    class TrackLikeTests {

        @Test
        @DisplayName("Successfully toggle track like for authenticated user")
        void shouldToggleTrackLike() {
            when(trackRepository.findById(10L)).thenReturn(Mono.just(testTrack));
            when(interactionRepository.findTopByUserIdAndTrackIdOrderByCreatedAtDesc(1L, 10L))
                    .thenReturn(Mono.empty());

            when(interactionRepository.save(any(Interaction.class))).thenAnswer(inv -> {
                Interaction entity = inv.getArgument(0);
                entity.setId(601L);
                return Mono.just(entity);
            });

            StepVerifier.create(interactionService.toggleLike(10L, true, null, 1L))
                    .assertNext(response -> {
                        assertThat(response.getId()).isEqualTo(601L);
                        assertThat(response.getUserId()).isEqualTo(1L);
                        assertThat(response.getTrackId()).isEqualTo(10L);
                        assertThat(response.getLiked()).isTrue();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Update existing interaction when unliking track")
        void shouldUpdateExistingWhenUnliking() {
            when(trackRepository.findById(10L)).thenReturn(Mono.just(testTrack));

            Interaction existing = Interaction.builder()
                    .id(602L)
                    .userId(1L)
                    .trackId(10L)
                    .liked(true)
                    .playedSeconds(50)
                    .build();

            when(interactionRepository.findTopByUserIdAndTrackIdOrderByCreatedAtDesc(1L, 10L))
                    .thenReturn(Mono.just(existing));

            when(interactionRepository.save(any(Interaction.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(interactionService.toggleLike(10L, false, null, 1L))
                    .assertNext(response -> {
                        assertThat(response.getId()).isEqualTo(602L);
                        assertThat(response.getLiked()).isFalse();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Lounge Co-Listening Tests")
    class CoListeningTests {

        @Test
        @DisplayName("Record co-listening interactions for all active participants and host in lounge")
        void shouldRecordCoListeningForAllActiveParticipantsAndHost() {
            when(djLoungeRepository.findById(5L)).thenReturn(Mono.just(testLounge)); // host is 100L
            when(trackRepository.findById(10L)).thenReturn(Mono.just(testTrack)); // duration 200s

            LoungeParticipant p1 = LoungeParticipant.builder()
                    .id(1L)
                    .loungeId(5L)
                    .userId(201L)
                    .role(LoungeParticipantRole.LISTENER)
                    .build();

            LoungeParticipant p2 = LoungeParticipant.builder()
                    .id(2L)
                    .loungeId(5L)
                    .userId(202L)
                    .role(LoungeParticipantRole.CO_DJ)
                    .build();

            when(loungeParticipantRepository.findByLoungeIdAndLeftAtIsNullOrderByJoinedAtAsc(5L))
                    .thenReturn(Flux.just(p1, p2));

            when(interactionRepository.saveAll(anyCollection())).thenAnswer(inv -> {
                Iterable<Interaction> entities = inv.getArgument(0);
                long id = 700L;
                List<Interaction> list = new java.util.ArrayList<>();
                for (Interaction e : entities) {
                    e.setId(++id);
                    list.add(e);
                }
                return Flux.fromIterable(list);
            });

            StepVerifier.create(interactionService.recordCoListening(5L, 10L, 200, true))
                    .recordWith(java.util.ArrayList::new)
                    .expectNextCount(3) // 2 participants (201, 202) + 1 host (100)
                    .consumeRecordedWith(results -> {
                        List<Long> userIds = results.stream().map(InteractionResponse::getUserId).toList();
                        assertThat(userIds).containsExactlyInAnyOrder(100L, 201L, 202L);

                        for (InteractionResponse r : results) {
                            assertThat(r.getTrackId()).isEqualTo(10L);
                            assertThat(r.getLoungeId()).isEqualTo(5L);
                            assertThat(r.getPlayedSeconds()).isEqualTo(200);
                            assertThat(r.getCompleted()).isTrue();
                        }
                    })
                    .verifyComplete();

            ArgumentCaptor<Iterable<Interaction>> captor = ArgumentCaptor.forClass(Iterable.class);
            verify(interactionRepository, times(1)).saveAll(captor.capture());

            List<Interaction> saved = (List<Interaction>) captor.getValue();
            assertThat(saved).hasSize(3);
        }

        @Test
        @DisplayName("Return empty flux when lounge or track ID is null")
        void shouldReturnEmptyWhenLoungeOrTrackNull() {
            StepVerifier.create(interactionService.recordCoListening(null, 10L, 100, true))
                    .verifyComplete();

            StepVerifier.create(interactionService.recordCoListening(5L, null, 100, true))
                    .verifyComplete();

            verify(interactionRepository, never()).saveAll(anyCollection());
        }
    }

    @Nested
    @DisplayName("Query Interaction History Tests")
    class QueryInteractionsTests {

        @Test
        @DisplayName("Retrieve interaction history for user")
        void shouldGetUserInteractions() {
            Interaction i1 = Interaction.builder()
                    .id(1L)
                    .userId(100L)
                    .trackId(10L)
                    .playedSeconds(180)
                    .completed(true)
                    .liked(true)
                    .createdAt(LocalDateTime.now())
                    .build();

            when(interactionRepository.findByUserIdOrderByCreatedAtDesc(100L))
                    .thenReturn(Flux.just(i1));
            when(trackRepository.findById(10L))
                    .thenReturn(Mono.just(testTrack));

            StepVerifier.create(interactionService.getUserInteractions(100L))
                    .assertNext(response -> {
                        assertThat(response.getId()).isEqualTo(1L);
                        assertThat(response.getUserId()).isEqualTo(100L);
                        assertThat(response.getTrackTitle()).isEqualTo("Midnight City");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Retrieve interaction history for lounge")
        void shouldGetLoungeInteractions() {
            Interaction i1 = Interaction.builder()
                    .id(2L)
                    .userId(100L)
                    .trackId(10L)
                    .loungeId(5L)
                    .playedSeconds(200)
                    .completed(true)
                    .createdAt(LocalDateTime.now())
                    .build();

            when(interactionRepository.findByLoungeIdOrderByCreatedAtDesc(5L))
                    .thenReturn(Flux.just(i1));
            when(trackRepository.findById(10L))
                    .thenReturn(Mono.just(testTrack));

            StepVerifier.create(interactionService.getLoungeInteractions(5L))
                    .assertNext(response -> {
                        assertThat(response.getId()).isEqualTo(2L);
                        assertThat(response.getLoungeId()).isEqualTo(5L);
                        assertThat(response.getTrackTitle()).isEqualTo("Midnight City");
                    })
                    .verifyComplete();
        }
    }
}
