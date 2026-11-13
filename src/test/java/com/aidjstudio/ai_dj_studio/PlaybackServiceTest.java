package com.aidjstudio.ai_dj_studio;

import com.aidjstudio.ai_dj_studio.dto.queue.LoungePlaybackStateResponse;
import com.aidjstudio.ai_dj_studio.dto.queue.QueueEntryResponse;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.entity.QueueEntry;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.QueueEntryRepository;
import com.aidjstudio.ai_dj_studio.service.LoungeQueueMutationSerializer;
import com.aidjstudio.ai_dj_studio.service.PlaybackService;
import com.aidjstudio.ai_dj_studio.service.QueueService;
import com.aidjstudio.ai_dj_studio.websocket.LoungeSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlaybackServiceTest {

    @Mock
    private DjLoungeRepository djLoungeRepository;

    @Mock
    private QueueEntryRepository queueEntryRepository;

    @Mock
    private QueueService queueService;

    @Mock
    private LoungeSessionManager loungeSessionManager;

    @Mock
    private LoungeQueueMutationSerializer mutationSerializer;

    @InjectMocks
    private PlaybackService playbackService;

    @BeforeEach
    void setUp() {
        lenient().when(mutationSerializer.executeSerialized(any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<Mono<?>>) inv.getArgument(1)).get());
        lenient().when(queueService.broadcastQueueUpdate(any(), any()))
                .thenReturn(Mono.just(Collections.emptyList()));
    }

    private DjLounge createLounge(Long loungeId, Long hostUserId, LoungeStatus status, Long currentTrackId) {
        DjLounge lounge = new DjLounge();
        lounge.setId(loungeId);
        lounge.setHostUserId(hostUserId);
        lounge.setStatus(status);
        lounge.setCurrentTrackId(currentTrackId);
        return lounge;
    }

    private QueueEntry createQueueEntry(Long id, Long loungeId, Long trackId, int position) {
        QueueEntry entry = new QueueEntry();
        entry.setId(id);
        entry.setLoungeId(loungeId);
        entry.setTrackId(trackId);
        entry.setPosition(position);
        entry.setVoteCount(0);
        entry.setAddedByUserId(200L);
        return entry;
    }

    @Test
    void getPlaybackState_returnsCurrentTrackAndOrderedNextUp() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);
        QueueEntry entry1 = createQueueEntry(10L, 1L, 502L, 1);
        QueueEntry entry2 = createQueueEntry(11L, 1L, 503L, 2);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L)).thenReturn(Flux.just(entry1, entry2));
        when(queueService.toResponseDto(entry1)).thenReturn(
                QueueEntryResponse.builder().id(10L).loungeId(1L).trackId(502L).position(1).build()
        );
        when(queueService.toResponseDto(entry2)).thenReturn(
                QueueEntryResponse.builder().id(11L).loungeId(1L).trackId(503L).position(2).build()
        );

        StepVerifier.create(playbackService.getPlaybackState(1L))
                .assertNext(state -> {
                    assertThat(state.getLoungeId()).isEqualTo(1L);
                    assertThat(state.getStatus()).isEqualTo(LoungeStatus.LIVE);
                    assertThat(state.getCurrentTrackId()).isEqualTo(501L);
                    assertThat(state.getNextUp()).hasSize(2);
                    assertThat(state.getNextUp().get(0).getId()).isEqualTo(10L);
                    assertThat(state.getNextUp().get(1).getId()).isEqualTo(11L);
                })
                .verifyComplete();
    }

    @Test
    void advanceToNextTrack_asHost_advancesHeadOfQueueAndNormalizesRemainingPositions() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);
        QueueEntry head = createQueueEntry(10L, 1L, 502L, 1);
        QueueEntry remaining = createQueueEntry(11L, 1L, 503L, 2);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L))
                .thenReturn(Flux.just(head, remaining))
                .thenReturn(Flux.just(remaining));
        when(queueEntryRepository.saveAll(any(Iterable.class))).thenAnswer(inv -> Flux.fromIterable(inv.getArgument(0)));
        when(queueEntryRepository.delete(head)).thenReturn(Mono.empty());
        when(djLoungeRepository.save(lounge)).thenReturn(Mono.just(lounge));

        when(queueService.toResponseDto(remaining)).thenReturn(
                QueueEntryResponse.builder().id(11L).loungeId(1L).trackId(503L).position(1).build()
        );

        StepVerifier.create(playbackService.advanceToNextTrack(1L, 100L))
                .assertNext(state -> {
                    assertThat(state.getCurrentTrackId()).isEqualTo(502L);
                    assertThat(state.getNextUp()).hasSize(1);
                    assertThat(state.getNextUp().get(0).getPosition()).isEqualTo(1);
                })
                .verifyComplete();

        verify(queueEntryRepository).delete(head);
        verify(djLoungeRepository).save(argThat(saved -> saved.getCurrentTrackId().equals(502L)));
        verify(queueService).broadcastQueueUpdate(eq(1L), eq("playback"));
    }

    @Test
    void advanceToNextTrack_whenQueueEmpty_throwsIllegalStateException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L)).thenReturn(Flux.empty());

        StepVerifier.create(playbackService.advanceToNextTrack(1L, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalStateException
                        && ex.getMessage().equals("Queue is empty"))
                .verify();

        verify(queueEntryRepository, never()).delete(any());
        verify(djLoungeRepository, never()).save(any());
    }

    @Test
    void advanceToNextTrack_whenNotHost_throwsAccessDeniedException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);
        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));

        StepVerifier.create(playbackService.advanceToNextTrack(1L, 200L))
                .expectError(AccessDeniedException.class)
                .verify();

        verify(queueEntryRepository, never()).delete(any());
    }

    @Test
    void advanceToNextTrack_whenNotLive_throwsIllegalStateException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.SCHEDULED, 501L);
        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));

        StepVerifier.create(playbackService.advanceToNextTrack(1L, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalStateException
                        && ex.getMessage().equals("Lounge must be LIVE to advance playback"))
                .verify();

        verify(queueEntryRepository, never()).delete(any());
    }

    @Test
    void advanceToNextTrack_whenEnded_throwsIllegalStateException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.ENDED, 501L);
        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));

        StepVerifier.create(playbackService.advanceToNextTrack(1L, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalStateException
                        && ex.getMessage().equals("Cannot modify playback of an ended lounge"))
                .verify();

        verify(queueEntryRepository, never()).delete(any());
    }

    @Test
    void advanceToNextTrack_whenQueueFrozen_throwsIllegalStateException() {
        when(loungeSessionManager.isQueueFrozen(1L)).thenReturn(true);

        StepVerifier.create(playbackService.advanceToNextTrack(1L, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalStateException
                        && ex.getMessage().equals("Queue mutations are frozen while host is disconnected"))
                .verify();

        verify(djLoungeRepository, never()).findById(anyLong());
    }

    @Test
    void promoteTrackToNowPlaying_asHost_removesTargetAndNormalizesRemainingPositions() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);
        QueueEntry target = createQueueEntry(15L, 1L, 999L, 2);
        QueueEntry other = createQueueEntry(16L, 1L, 888L, 3);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByIdAndLoungeId(15L, 1L)).thenReturn(Mono.just(target));
        when(queueEntryRepository.delete(target)).thenReturn(Mono.empty());
        when(djLoungeRepository.save(lounge)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L))
                .thenReturn(Flux.just(other))
                .thenReturn(Flux.just(other));
        when(queueEntryRepository.saveAll(any(Iterable.class))).thenAnswer(inv -> Flux.fromIterable(inv.getArgument(0)));

        when(queueService.toResponseDto(other)).thenReturn(
                QueueEntryResponse.builder().id(16L).loungeId(1L).trackId(888L).position(1).build()
        );

        StepVerifier.create(playbackService.promoteTrackToNowPlaying(1L, 15L, 100L))
                .assertNext(state -> {
                    assertThat(state.getCurrentTrackId()).isEqualTo(999L);
                    assertThat(state.getNextUp()).hasSize(1);
                })
                .verifyComplete();

        verify(queueEntryRepository).delete(target);
        verify(djLoungeRepository).save(argThat(saved -> saved.getCurrentTrackId().equals(999L)));
        verify(queueService).broadcastQueueUpdate(eq(1L), eq("playback"));
    }

    @Test
    void promoteTrackToNowPlaying_whenTargetNotFound_throwsIllegalArgumentException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByIdAndLoungeId(999L, 1L)).thenReturn(Mono.empty());

        StepVerifier.create(playbackService.promoteTrackToNowPlaying(1L, 999L, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException
                        && ex.getMessage().contains("Queue entry not found with id: 999"))
                .verify();

        verify(queueEntryRepository, never()).delete(any());
        verify(djLoungeRepository, never()).save(any());
    }

    @Test
    void promoteTrackToNowPlaying_whenNotHost_throwsAccessDeniedException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);
        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));

        StepVerifier.create(playbackService.promoteTrackToNowPlaying(1L, 15L, 200L))
                .expectError(AccessDeniedException.class)
                .verify();

        verify(queueEntryRepository, never()).delete(any());
    }

    @Test
    void promoteTrackToNowPlaying_whenNotLive_throwsIllegalStateException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.SCHEDULED, 501L);
        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));

        StepVerifier.create(playbackService.promoteTrackToNowPlaying(1L, 15L, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalStateException
                        && ex.getMessage().equals("Lounge must be LIVE to promote a track"))
                .verify();

        verify(queueEntryRepository, never()).delete(any());
    }

    @Test
    void promoteTrackToNowPlaying_whenEnded_throwsIllegalStateException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.ENDED, 501L);
        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));

        StepVerifier.create(playbackService.promoteTrackToNowPlaying(1L, 15L, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalStateException
                        && ex.getMessage().equals("Cannot modify playback of an ended lounge"))
                .verify();

        verify(queueEntryRepository, never()).delete(any());
    }

    @Test
    void promoteTrackToNowPlaying_whenQueueFrozen_throwsIllegalStateException() {
        when(loungeSessionManager.isQueueFrozen(1L)).thenReturn(true);

        StepVerifier.create(playbackService.promoteTrackToNowPlaying(1L, 15L, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalStateException
                        && ex.getMessage().equals("Queue mutations are frozen while host is disconnected"))
                .verify();

        verify(djLoungeRepository, never()).findById(anyLong());
    }
}
