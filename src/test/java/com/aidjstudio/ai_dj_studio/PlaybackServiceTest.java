package com.aidjstudio.ai_dj_studio;

import com.aidjstudio.ai_dj_studio.dto.queue.QueueEntryResponse;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.entity.QueueEntry;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.QueueEntryRepository;
import com.aidjstudio.ai_dj_studio.service.PlaybackService;
import com.aidjstudio.ai_dj_studio.service.QueueService;
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

    private QueueEntry createEntry(Long id, Long loungeId, Long trackId, Integer position) {
        QueueEntry entry = new QueueEntry();
        entry.setId(id);
        entry.setLoungeId(loungeId);
        entry.setTrackId(trackId);
        entry.setPosition(position);
        entry.setVoteCount(0);
        entry.setAddedByUserId(200L);
        return entry;
    }

    // --- GET PLAYBACK STATE TESTS ---

    @Test
    void getPlaybackState_whenValid_returnsNowPlayingAndNextUp() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);
        QueueEntry entry1 = createEntry(10L, 1L, 601L, 1);
        QueueEntry entry2 = createEntry(11L, 1L, 602L, 2);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L)).thenReturn(Flux.just(entry1, entry2));

        when(queueService.toResponseDto(entry1)).thenReturn(
                new QueueEntryResponse(10L, 1L, 601L, 1, 0, 200L)
        );
        when(queueService.toResponseDto(entry2)).thenReturn(
                new QueueEntryResponse(11L, 1L, 602L, 2, 0, 200L)
        );

        StepVerifier.create(playbackService.getPlaybackState(1L))
                .expectNextMatches(res -> res.getLoungeId().equals(1L)
                        && res.getStatus() == LoungeStatus.LIVE
                        && res.getCurrentTrackId().equals(501L)
                        && res.getNextUp().size() == 2
                        && res.getNextUp().get(0).getTrackId().equals(601L)
                        && res.getNextUp().get(1).getTrackId().equals(602L))
                .verifyComplete();
    }

    @Test
    void getPlaybackState_loungeNotFound_throwsIllegalArgumentException() {
        when(djLoungeRepository.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(playbackService.getPlaybackState(999L))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException
                        && ex.getMessage().contains("Lounge not found: 999"))
                .verify();
    }

    // --- ADVANCE TO NEXT TRACK TESTS ---

    @Test
    void advanceToNextTrack_whenLiveAndHasQueue_promotesNextTrackAndRemovesFromQueue() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);
        QueueEntry nextEntry = createEntry(10L, 1L, 601L, 1);
        QueueEntry remainingEntry = createEntry(11L, 1L, 602L, 2);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L))
                .thenReturn(Flux.just(nextEntry, remainingEntry))
                .thenReturn(Flux.just(remainingEntry));

        when(queueEntryRepository.saveAll(anyList())).thenAnswer(inv -> Flux.fromIterable(inv.getArgument(0)));
        when(queueEntryRepository.delete(nextEntry)).thenReturn(Mono.empty());
        when(djLoungeRepository.save(lounge)).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        when(queueService.toResponseDto(remainingEntry)).thenReturn(
                new QueueEntryResponse(11L, 1L, 602L, 1, 0, 200L)
        );

        StepVerifier.create(playbackService.advanceToNextTrack(1L, 100L))
                .expectNextMatches(res -> res.getCurrentTrackId().equals(601L)
                        && res.getNextUp().size() == 1
                        && res.getNextUp().get(0).getTrackId().equals(602L))
                .verifyComplete();

        assertEquals(601L, lounge.getCurrentTrackId());
        verify(queueEntryRepository).delete(nextEntry);
        verify(djLoungeRepository).save(lounge);
    }

    @Test
    void advanceToNextTrack_whenQueueEmpty_throwsIllegalStateExceptionAndKeepsCurrentTrack() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L)).thenReturn(Flux.empty());

        StepVerifier.create(playbackService.advanceToNextTrack(1L, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalStateException
                        && ex.getMessage().equals("Queue is empty"))
                .verify();

        assertEquals(501L, lounge.getCurrentTrackId());
        verify(queueEntryRepository, never()).delete(any());
        verify(djLoungeRepository, never()).save(any());
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
    void advanceToNextTrack_whenNotHost_throwsAccessDeniedException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);
        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));

        StepVerifier.create(playbackService.advanceToNextTrack(1L, 200L))
                .expectErrorMatches(ex -> ex instanceof AccessDeniedException
                        && ex.getMessage().equals("Only the lounge host can control playback"))
                .verify();

        verify(queueEntryRepository, never()).delete(any());
    }

    // --- PROMOTE TRACK TO NOW PLAYING TESTS ---

    @Test
    void promoteTrackToNowPlaying_whenValid_setsCurrentTrackAndRemovesFromQueue() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);
        QueueEntry entryToPromote = createEntry(15L, 1L, 777L, 2);
        QueueEntry remainingEntry = createEntry(16L, 1L, 888L, 3);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByIdAndLoungeId(15L, 1L)).thenReturn(Mono.just(entryToPromote));
        when(queueEntryRepository.delete(entryToPromote)).thenReturn(Mono.empty());
        when(djLoungeRepository.save(lounge)).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L))
                .thenReturn(Flux.just(remainingEntry))
                .thenReturn(Flux.just(remainingEntry));

        when(queueEntryRepository.saveAll(anyList())).thenAnswer(inv -> Flux.fromIterable(inv.getArgument(0)));
        when(queueService.toResponseDto(remainingEntry)).thenReturn(
                new QueueEntryResponse(16L, 1L, 888L, 1, 0, 200L)
        );

        StepVerifier.create(playbackService.promoteTrackToNowPlaying(1L, 15L, 100L))
                .expectNextMatches(res -> res.getCurrentTrackId().equals(777L)
                        && res.getNextUp().size() == 1
                        && res.getNextUp().get(0).getTrackId().equals(888L))
                .verifyComplete();

        assertEquals(777L, lounge.getCurrentTrackId());
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
                        && ex.getMessage().contains("Queue entry not found with id: 999"))
                .verify();

        verify(queueEntryRepository, never()).delete(any());
    }

    @Test
    void promoteTrackToNowPlaying_whenNotHost_throwsAccessDeniedException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE, 501L);
        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));

        StepVerifier.create(playbackService.promoteTrackToNowPlaying(1L, 15L, 200L))
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
}
