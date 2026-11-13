package com.aidjstudio.ai_dj_studio;

import com.aidjstudio.ai_dj_studio.dto.queue.QueueEntryResponse;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.entity.QueueEntry;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.QueueEntryRepository;
import com.aidjstudio.ai_dj_studio.service.PlaybackService;
import com.aidjstudio.ai_dj_studio.service.QueueService;
import com.aidjstudio.ai_dj_studio.websocket.LoungeSessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
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

    @InjectMocks
    private PlaybackService playbackService;

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
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L))
                .thenReturn(Flux.just(entry1, entry2));
        when(queueService.toResponseDto(entry1))
                .thenReturn(QueueEntryResponse.builder().id(10L).position(1).build());
        when(queueService.toResponseDto(entry2))
                .thenReturn(QueueEntryResponse.builder().id(11L).position(2).build());

        StepVerifier.create(playbackService.getPlaybackState(1L))
                .assertNext(response -> {
                    assertEquals(1L, response.getLoungeId());
                    assertEquals(LoungeStatus.LIVE, response.getStatus());
                    assertEquals(501L, response.getCurrentTrackId());
                    assertEquals(2, response.getNextUp().size());
                    assertEquals(10L, response.getNextUp().get(0).getId());
                    assertEquals(11L, response.getNextUp().get(1).getId());
                })
                .verifyComplete();
    }

    @Test
    void advanceToNextTrack_success_promotesFirstEntryAndReindexesRemaining() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);
        QueueEntry first = createQueueEntry(10L, 1L, 502L, 1);
        QueueEntry second = createQueueEntry(11L, 1L, 503L, 2);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L))
                .thenReturn(Flux.just(first, second));
        when(queueEntryRepository.saveAll(any(Iterable.class)))
                .thenAnswer(inv -> Flux.fromIterable(inv.getArgument(0)));
        when(queueEntryRepository.delete(first)).thenReturn(Mono.empty());
        when(djLoungeRepository.save(lounge)).thenReturn(Mono.just(lounge));
        when(queueService.toResponseDto(any(QueueEntry.class)))
                .thenReturn(QueueEntryResponse.builder().id(11L).position(1).build());

        StepVerifier.create(playbackService.advanceToNextTrack(1L, 100L))
                .assertNext(response -> {
                    assertEquals(502L, response.getCurrentTrackId());
                    assertEquals(1, second.getPosition());
                })
                .verifyComplete();

        verify(queueEntryRepository).delete(first);
        verify(djLoungeRepository).save(lounge);
    }

    @Test
    void advanceToNextTrack_whenQueueEmpty_throwsIllegalStateException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);
        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L))
                .thenReturn(Flux.empty());

        StepVerifier.create(playbackService.advanceToNextTrack(1L, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalStateException
                        && ex.getMessage().equals("Queue is empty"))
                .verify();

        verify(djLoungeRepository, never()).save(any());
    }

    @Test
    void advanceToNextTrack_whenCallerNotHost_throwsAccessDeniedException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);
        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));

        StepVerifier.create(playbackService.advanceToNextTrack(1L, 999L))
                .expectErrorMatches(ex -> ex instanceof AccessDeniedException
                        && ex.getMessage().equals("Only the lounge host can control playback"))
                .verify();

        verify(queueEntryRepository, never()).findByLoungeIdOrderByPositionAsc(any());
    }

    @Test
    void advanceToNextTrack_whenScheduled_throwsIllegalStateException() {
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
    void promoteTrackToNowPlaying_success_deletesEntrySetsNowPlayingAndNormalizesPositions() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);
        QueueEntry entryToPromote = createQueueEntry(15L, 1L, 555L, 2);
        QueueEntry otherEntry = createQueueEntry(10L, 1L, 502L, 1);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByIdAndLoungeId(15L, 1L)).thenReturn(Mono.just(entryToPromote));
        when(queueEntryRepository.delete(entryToPromote)).thenReturn(Mono.empty());
        when(djLoungeRepository.save(lounge)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L))
                .thenReturn(Flux.just(otherEntry));
        when(queueEntryRepository.saveAll(any(Iterable.class)))
                .thenAnswer(inv -> Flux.fromIterable(inv.getArgument(0)));
        when(queueService.toResponseDto(otherEntry))
                .thenReturn(QueueEntryResponse.builder().id(10L).position(1).build());

        StepVerifier.create(playbackService.promoteTrackToNowPlaying(1L, 15L, 100L))
                .assertNext(response -> {
                    assertEquals(555L, response.getCurrentTrackId());
                    assertEquals(1, otherEntry.getPosition());
                })
                .verifyComplete();

        verify(queueEntryRepository).delete(entryToPromote);
        verify(djLoungeRepository).save(lounge);
    }

    @Test
    void promoteTrackToNowPlaying_whenEntryNotFound_throwsIllegalArgumentException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);
        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByIdAndLoungeId(999L, 1L)).thenReturn(Mono.empty());

        StepVerifier.create(playbackService.promoteTrackToNowPlaying(1L, 999L, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException
                        && ex.getMessage().equals("Queue entry not found with id: 999"))
                .verify();

        verify(queueEntryRepository, never()).delete(any());
    }

    @Test
    void promoteTrackToNowPlaying_whenCallerNotHost_throwsAccessDeniedException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);
        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));

        StepVerifier.create(playbackService.promoteTrackToNowPlaying(1L, 15L, 999L))
                .expectErrorMatches(ex -> ex instanceof AccessDeniedException
                        && ex.getMessage().equals("Only the lounge host can control playback"))
                .verify();

        verify(queueEntryRepository, never()).delete(any());
    }

    @Test
    void promoteTrackToNowPlaying_whenScheduled_throwsIllegalStateException() {
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
