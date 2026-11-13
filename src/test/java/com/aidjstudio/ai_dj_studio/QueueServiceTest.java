package com.aidjstudio.ai_dj_studio;

import com.aidjstudio.ai_dj_studio.dto.queue.AddQueueEntryRequest;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsQueueUpdatePayload;
import com.aidjstudio.ai_dj_studio.entity.*;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.repository.QueueEntryRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.service.LoungeQueueMutationSerializer;
import com.aidjstudio.ai_dj_studio.service.QueueService;
import com.aidjstudio.ai_dj_studio.websocket.LoungeSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueueServiceTest {

    @Mock
    private QueueEntryRepository queueEntryRepository;

    @Mock
    private DjLoungeRepository djLoungeRepository;

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private LoungeParticipantRepository loungeParticipantRepository;

    @Mock
    private LoungeSessionManager loungeSessionManager;

    @Mock
    private LoungeQueueMutationSerializer mutationSerializer;

    @InjectMocks
    private QueueService queueService;

    @BeforeEach
    void setUp() {
        lenient().when(mutationSerializer.executeSerialized(any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<Mono<?>>) inv.getArgument(1)).get());
        lenient().when(mutationSerializer.executeSerializedFlux(any(), any()))
                .thenAnswer(inv -> ((java.util.function.Supplier<Flux<?>>) inv.getArgument(1)).get());
        lenient().when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(any()))
                .thenReturn(Flux.empty());
    }

    private DjLounge createLounge(Long loungeId, Long hostUserId, LoungeStatus status) {
        DjLounge lounge = new DjLounge();
        lounge.setId(loungeId);
        lounge.setHostUserId(hostUserId);
        lounge.setStatus(status);
        return lounge;
    }

    private Track createTrack(Long trackId) {
        Track track = new Track();
        track.setId(trackId);
        track.setTitle("Test Track " + trackId);
        track.setArtist("Test Artist");
        track.setDurationSeconds(180);
        track.setAudioAssetUrl("https://example.com/audio.mp3");
        track.setBpm(new BigDecimal("128.00"));
        track.setCamelotKey("8B");
        track.setEnergy(new BigDecimal("0.75"));
        track.setValence(new BigDecimal("0.60"));
        return track;
    }

    private QueueEntry createEntry(Long id, Long loungeId, Long trackId, Integer position, Integer votes, Long addedBy) {
        QueueEntry entry = new QueueEntry();
        entry.setId(id);
        entry.setLoungeId(loungeId);
        entry.setTrackId(trackId);
        entry.setPosition(position);
        entry.setVoteCount(votes);
        entry.setAddedByUserId(addedBy);
        return entry;
    }

    private LoungeParticipant createParticipant(Long loungeId, Long userId) {
        LoungeParticipant participant = new LoungeParticipant();
        participant.setId(10L);
        participant.setLoungeId(loungeId);
        participant.setUserId(userId);
        participant.setRole(LoungeParticipantRole.LISTENER);
        participant.setJoinedAt(LocalDateTime.now());
        participant.setLeftAt(null);
        return participant;
    }

    // --- GET QUEUE TESTS ---

    @Test
    void getQueue_empty_shouldReturnEmptyFlux() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);
        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L)).thenReturn(Flux.empty());

        StepVerifier.create(queueService.getQueue(1L))
                .verifyComplete();

        verify(queueEntryRepository).findByLoungeIdOrderByPositionAsc(1L);
    }

    @Test
    void getQueue_nonEmpty_shouldReturnEntriesOrderedByPositionAsc() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);
        QueueEntry entry1 = createEntry(101L, 1L, 201L, 1, 0, 100L);
        QueueEntry entry2 = createEntry(102L, 1L, 202L, 2, 3, 200L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L)).thenReturn(Flux.just(entry1, entry2));

        StepVerifier.create(queueService.getQueue(1L))
                .expectNextMatches(dto -> dto.getId().equals(101L) && dto.getPosition() == 1 && dto.getTrackId().equals(201L))
                .expectNextMatches(dto -> dto.getId().equals(102L) && dto.getPosition() == 2 && dto.getTrackId().equals(202L))
                .verifyComplete();
    }

    @Test
    void getQueue_loungeNotFound_shouldThrowIllegalArgumentException() {
        when(djLoungeRepository.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(queueService.getQueue(999L))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException && ex.getMessage().contains("Lounge not found: 999"))
                .verify();

        verify(queueEntryRepository, never()).findByLoungeIdOrderByPositionAsc(any());
    }

    @Test
    void getQueue_endedLounge_shouldReturnFinalQueueSnapshot() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.ENDED);
        QueueEntry entry = createEntry(101L, 1L, 201L, 1, 5, 100L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L)).thenReturn(Flux.just(entry));

        StepVerifier.create(queueService.getQueue(1L))
                .expectNextMatches(dto -> dto.getId().equals(101L) && dto.getVoteCount() == 5)
                .verifyComplete();
    }

    // --- ADD TRACK TESTS ---

    @Test
    void addTrackToQueue_asHost_shouldSucceedAndSetPosition() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);
        Track track = createTrack(50L);
        AddQueueEntryRequest request = new AddQueueEntryRequest(50L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(trackRepository.findById(50L)).thenReturn(Mono.just(track));
        when(queueEntryRepository.existsByLoungeIdAndTrackId(1L, 50L)).thenReturn(Mono.just(false));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L)).thenReturn(Flux.empty());

        when(queueEntryRepository.save(any(QueueEntry.class))).thenAnswer(inv -> {
            QueueEntry saved = inv.getArgument(0);
            saved.setId(1L);
            return Mono.just(saved);
        });

        StepVerifier.create(queueService.addTrackToQueue(1L, request, 100L))
                .expectNextMatches(res -> res.getPosition() == 1
                        && res.getTrackId().equals(50L)
                        && res.getAddedByUserId().equals(100L)
                        && res.getVoteCount() == 0)
                .verifyComplete();

        verify(queueEntryRepository).save(any(QueueEntry.class));
    }

    @Test
    void addTrackToQueue_asActiveParticipant_shouldSucceed() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);
        Track track = createTrack(50L);
        AddQueueEntryRequest request = new AddQueueEntryRequest(50L);
        LoungeParticipant participant = createParticipant(1L, 200L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(1L, 200L))
                .thenReturn(Mono.just(participant));
        when(trackRepository.findById(50L)).thenReturn(Mono.just(track));
        when(queueEntryRepository.existsByLoungeIdAndTrackId(1L, 50L)).thenReturn(Mono.just(false));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L)).thenReturn(Flux.empty());

        when(queueEntryRepository.save(any(QueueEntry.class))).thenAnswer(inv -> {
            QueueEntry saved = inv.getArgument(0);
            saved.setId(2L);
            return Mono.just(saved);
        });

        StepVerifier.create(queueService.addTrackToQueue(1L, request, 200L))
                .expectNextMatches(res -> res.getAddedByUserId().equals(200L))
                .verifyComplete();

        verify(queueEntryRepository).save(any(QueueEntry.class));
    }

    @Test
    void addTrackToQueue_userNotParticipantOrHost_shouldThrowAccessDeniedException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);
        AddQueueEntryRequest request = new AddQueueEntryRequest(50L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(1L, 999L))
                .thenReturn(Mono.empty());

        StepVerifier.create(queueService.addTrackToQueue(1L, request, 999L))
                .expectErrorMatches(ex -> ex instanceof AccessDeniedException
                        && ex.getMessage().equals("User is not an active participant of this lounge"))
                .verify();

        verify(queueEntryRepository, never()).save(any());
    }

    @Test
    void addTrackToQueue_loungeNotFound_shouldThrowIllegalArgumentException() {
        AddQueueEntryRequest request = new AddQueueEntryRequest(50L);
        when(djLoungeRepository.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(queueService.addTrackToQueue(999L, request, 100L))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(queueEntryRepository, never()).save(any());
    }

    @Test
    void addTrackToQueue_endedLounge_shouldThrowIllegalStateException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.ENDED);
        AddQueueEntryRequest request = new AddQueueEntryRequest(50L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));

        StepVerifier.create(queueService.addTrackToQueue(1L, request, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalStateException
                        && ex.getMessage().equals("Cannot modify queue of an ended lounge"))
                .verify();

        verify(queueEntryRepository, never()).save(any());
    }

    @Test
    void addTrackToQueue_trackNotFound_shouldThrowIllegalArgumentException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);
        AddQueueEntryRequest request = new AddQueueEntryRequest(9999L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(trackRepository.findById(9999L)).thenReturn(Mono.empty());

        StepVerifier.create(queueService.addTrackToQueue(1L, request, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException
                        && ex.getMessage().contains("Track not found with id: 9999"))
                .verify();

        verify(queueEntryRepository, never()).save(any());
    }

    @Test
    void addTrackToQueue_duplicateTrack_shouldThrowIllegalStateExceptionWithExactMessage() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);
        Track track = createTrack(50L);
        AddQueueEntryRequest request = new AddQueueEntryRequest(50L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(trackRepository.findById(50L)).thenReturn(Mono.just(track));
        when(queueEntryRepository.existsByLoungeIdAndTrackId(1L, 50L)).thenReturn(Mono.just(true));

        StepVerifier.create(queueService.addTrackToQueue(1L, request, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalStateException
                        && ex.getMessage().equals("Track is already in queue"))
                .verify();

        verify(queueEntryRepository, never()).save(any());
    }

    @Test
    void addTrackToQueue_computesCorrectNextSparsePositionWhenQueueAlreadyHasEntries() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);
        Track track = createTrack(50L);
        AddQueueEntryRequest request = new AddQueueEntryRequest(50L);

        QueueEntry existing1 = createEntry(1L, 1L, 10L, 1, 0, 100L);
        QueueEntry existing2 = createEntry(2L, 1L, 20L, 5, 0, 100L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(trackRepository.findById(50L)).thenReturn(Mono.just(track));
        when(queueEntryRepository.existsByLoungeIdAndTrackId(1L, 50L)).thenReturn(Mono.just(false));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L)).thenReturn(Flux.just(existing1, existing2));

        when(queueEntryRepository.save(any(QueueEntry.class))).thenAnswer(inv -> {
            QueueEntry saved = inv.getArgument(0);
            saved.setId(3L);
            return Mono.just(saved);
        });

        StepVerifier.create(queueService.addTrackToQueue(1L, request, 100L))
                .expectNextMatches(res -> res.getPosition() == 6)
                .verifyComplete();
    }

    // --- REMOVE QUEUE ENTRY TESTS ---

    @Test
    void removeQueueEntry_asHost_shouldSucceed() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);
        QueueEntry entry = createEntry(5L, 1L, 50L, 1, 0, 200L); // Added by user 200L

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByIdAndLoungeId(5L, 1L)).thenReturn(Mono.just(entry));
        when(queueEntryRepository.delete(entry)).thenReturn(Mono.empty());

        StepVerifier.create(queueService.removeQueueEntry(1L, 5L, 100L))
                .verifyComplete();

        verify(queueEntryRepository).delete(entry);
    }

    @Test
    void removeQueueEntry_asOriginalSubmitter_shouldSucceed() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);
        QueueEntry entry = createEntry(5L, 1L, 50L, 1, 0, 200L); // Added by user 200L

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByIdAndLoungeId(5L, 1L)).thenReturn(Mono.just(entry));
        when(queueEntryRepository.delete(entry)).thenReturn(Mono.empty());

        StepVerifier.create(queueService.removeQueueEntry(1L, 5L, 200L))
                .verifyComplete();

        verify(queueEntryRepository).delete(entry);
    }

    @Test
    void removeQueueEntry_asOtherParticipant_shouldThrowAccessDeniedException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);
        QueueEntry entry = createEntry(5L, 1L, 50L, 1, 0, 200L); // Added by user 200L

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByIdAndLoungeId(5L, 1L)).thenReturn(Mono.just(entry));

        StepVerifier.create(queueService.removeQueueEntry(1L, 5L, 300L))
                .expectErrorMatches(ex -> ex instanceof AccessDeniedException
                        && ex.getMessage().equals("Only the lounge host or the user who added this track can remove it"))
                .verify();

        verify(queueEntryRepository, never()).delete(any());
    }

    @Test
    void removeQueueEntry_loungeNotFound_shouldThrowIllegalArgumentException() {
        when(djLoungeRepository.findById(999L)).thenReturn(Mono.empty());

        StepVerifier.create(queueService.removeQueueEntry(999L, 5L, 100L))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(queueEntryRepository, never()).delete(any());
    }

    @Test
    void removeQueueEntry_endedLounge_shouldThrowIllegalStateException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.ENDED);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));

        StepVerifier.create(queueService.removeQueueEntry(1L, 5L, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalStateException
                        && ex.getMessage().equals("Cannot modify queue of an ended lounge"))
                .verify();

        verify(queueEntryRepository, never()).delete(any());
    }

    @Test
    void removeQueueEntry_entryNotFound_shouldThrowIllegalArgumentException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByIdAndLoungeId(999L, 1L)).thenReturn(Mono.empty());

        StepVerifier.create(queueService.removeQueueEntry(1L, 999L, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException
                        && ex.getMessage().contains("Queue entry not found with id: 999"))
                .verify();

        verify(queueEntryRepository, never()).delete(any());
    }

    // --- REORDER QUEUE ENTRY TESTS ---

    @Test
    void reorderQueueEntry_asHost_shouldShiftInterveningEntriesAndNormalizePositions() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);
        QueueEntry e1 = createEntry(10L, 1L, 100L, 1, 0, 100L);
        QueueEntry e2 = createEntry(20L, 1L, 200L, 2, 0, 100L);
        QueueEntry e3 = createEntry(30L, 1L, 300L, 3, 0, 100L);
        QueueEntry e4 = createEntry(40L, 1L, 400L, 4, 0, 100L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L))
                .thenReturn(Flux.just(e1, e2, e3, e4));

        when(queueEntryRepository.saveAll(any(Iterable.class))).thenAnswer(inv -> {
            Iterable<QueueEntry> saved = inv.getArgument(0);
            return Flux.fromIterable(saved);
        });

        StepVerifier.create(queueService.reorderQueueEntry(1L, 40L, 2, 100L))
                .expectNextMatches(dto -> dto.getId().equals(10L) && dto.getPosition() == 1)
                .expectNextMatches(dto -> dto.getId().equals(40L) && dto.getPosition() == 2)
                .expectNextMatches(dto -> dto.getId().equals(20L) && dto.getPosition() == 3)
                .expectNextMatches(dto -> dto.getId().equals(30L) && dto.getPosition() == 4)
                .verifyComplete();
    }

    @Test
    void reorderQueueEntry_asNonHost_shouldThrowAccessDeniedException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));

        StepVerifier.create(queueService.reorderQueueEntry(1L, 10L, 2, 200L))
                .expectErrorMatches(ex -> ex instanceof AccessDeniedException
                        && ex.getMessage().equals("Only the lounge host can reorder the queue"))
                .verify();

        verify(queueEntryRepository, never()).saveAll(any(Iterable.class));
    }

    @Test
    void reorderQueueEntry_endedLounge_shouldThrowIllegalStateException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.ENDED);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));

        StepVerifier.create(queueService.reorderQueueEntry(1L, 10L, 2, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalStateException
                        && ex.getMessage().equals("Cannot modify queue of an ended lounge"))
                .verify();

        verify(queueEntryRepository, never()).saveAll(any(Iterable.class));
    }

    @Test
    void reorderQueueEntry_entryNotFound_shouldThrowIllegalArgumentException() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);
        QueueEntry e1 = createEntry(10L, 1L, 100L, 1, 0, 100L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L)).thenReturn(Flux.just(e1));

        StepVerifier.create(queueService.reorderQueueEntry(1L, 999L, 2, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalArgumentException
                        && ex.getMessage().contains("Queue entry not found with id: 999"))
                .verify();

        verify(queueEntryRepository, never()).saveAll(any(Iterable.class));
    }

    @Test
    void reorderQueueEntry_clampedTargetPosition_shouldClampGracefully() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);
        QueueEntry e1 = createEntry(10L, 1L, 100L, 1, 0, 100L);
        QueueEntry e2 = createEntry(20L, 1L, 200L, 2, 0, 100L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L)).thenReturn(Flux.just(e1, e2));
        when(queueEntryRepository.saveAll(any(Iterable.class))).thenAnswer(inv -> Flux.fromIterable(inv.getArgument(0)));

        StepVerifier.create(queueService.reorderQueueEntry(1L, 10L, 999, 100L))
                .expectNextMatches(dto -> dto.getId().equals(20L) && dto.getPosition() == 1)
                .expectNextMatches(dto -> dto.getId().equals(10L) && dto.getPosition() == 2)
                .verifyComplete();
    }

    // --- MOVE TO TOP TESTS ---

    @Test
    void moveQueueEntryToTop_asHost_shouldMoveEntryToPositionOne() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);
        QueueEntry e1 = createEntry(10L, 1L, 100L, 1, 0, 100L);
        QueueEntry e2 = createEntry(20L, 1L, 200L, 2, 0, 100L);
        QueueEntry e3 = createEntry(30L, 1L, 300L, 3, 0, 100L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L)).thenReturn(Flux.just(e1, e2, e3));
        when(queueEntryRepository.saveAll(any(Iterable.class))).thenAnswer(inv -> Flux.fromIterable(inv.getArgument(0)));

        StepVerifier.create(queueService.moveQueueEntryToTop(1L, 30L, 100L))
                .expectNextMatches(dto -> dto.getId().equals(30L) && dto.getPosition() == 1)
                .expectNextMatches(dto -> dto.getId().equals(10L) && dto.getPosition() == 2)
                .expectNextMatches(dto -> dto.getId().equals(20L) && dto.getPosition() == 3)
                .verifyComplete();
    }

    // --- QUEUE FROZEN TESTS ---

    @Test
    void addTrackToQueue_whenQueueFrozen_shouldThrowIllegalStateException() {
        when(loungeSessionManager.isQueueFrozen(1L)).thenReturn(true);

        AddQueueEntryRequest request = new AddQueueEntryRequest(50L);
        StepVerifier.create(queueService.addTrackToQueue(1L, request, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalStateException
                        && ex.getMessage().equals("Queue mutations are frozen while host is disconnected"))
                .verify();

        verify(djLoungeRepository, never()).findById(anyLong());
    }

    @Test
    void removeQueueEntry_whenQueueFrozen_shouldThrowIllegalStateException() {
        when(loungeSessionManager.isQueueFrozen(1L)).thenReturn(true);

        StepVerifier.create(queueService.removeQueueEntry(1L, 10L, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalStateException
                        && ex.getMessage().equals("Queue mutations are frozen while host is disconnected"))
                .verify();

        verify(djLoungeRepository, never()).findById(anyLong());
    }

    @Test
    void reorderQueueEntry_whenQueueFrozen_shouldThrowIllegalStateException() {
        when(loungeSessionManager.isQueueFrozen(1L)).thenReturn(true);

        StepVerifier.create(queueService.reorderQueueEntry(1L, 10L, 2, 100L))
                .expectErrorMatches(ex -> ex instanceof IllegalStateException
                        && ex.getMessage().equals("Queue mutations are frozen while host is disconnected"))
                .verify();

        verify(djLoungeRepository, never()).findById(anyLong());
    }

    // --- F08 BROADCAST TESTS ---

    @Test
    @SuppressWarnings("unchecked")
    void addTrackToQueue_shouldBroadcastQueueUpdateWithReasonManual() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);
        Track track = createTrack(50L);
        AddQueueEntryRequest request = new AddQueueEntryRequest(50L);
        QueueEntry savedEntry = createEntry(1L, 1L, 50L, 1, 0, 100L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(trackRepository.findById(50L)).thenReturn(Mono.just(track));
        when(queueEntryRepository.existsByLoungeIdAndTrackId(1L, 50L)).thenReturn(Mono.just(false));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L)).thenReturn(Flux.just(savedEntry));
        when(queueEntryRepository.save(any(QueueEntry.class))).thenReturn(Mono.just(savedEntry));

        StepVerifier.create(queueService.addTrackToQueue(1L, request, 100L))
                .expectNextCount(1)
                .verifyComplete();

        ArgumentCaptor<WsEventEnvelope<?>> captor = ArgumentCaptor.forClass(WsEventEnvelope.class);
        verify(loungeSessionManager).broadcast(eq(1L), captor.capture());

        WsEventEnvelope<?> envelope = captor.getValue();
        assertThat(envelope.getEvent()).isEqualTo("lounge.queue.update");
        assertThat(envelope.getPayload()).isInstanceOf(WsQueueUpdatePayload.class);
        WsQueueUpdatePayload payload = (WsQueueUpdatePayload) envelope.getPayload();
        assertThat(payload.getReason()).isEqualTo("manual");
        assertThat(payload.getQueue()).hasSize(1);
        assertThat(payload.getQueue().get(0).getTrackId()).isEqualTo(50L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void removeQueueEntry_shouldBroadcastQueueUpdateWithReasonManual() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);
        QueueEntry entry = createEntry(5L, 1L, 50L, 1, 0, 100L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByIdAndLoungeId(5L, 1L)).thenReturn(Mono.just(entry));
        when(queueEntryRepository.delete(entry)).thenReturn(Mono.empty());
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L)).thenReturn(Flux.empty());

        StepVerifier.create(queueService.removeQueueEntry(1L, 5L, 100L))
                .verifyComplete();

        ArgumentCaptor<WsEventEnvelope<?>> captor = ArgumentCaptor.forClass(WsEventEnvelope.class);
        verify(loungeSessionManager).broadcast(eq(1L), captor.capture());

        WsEventEnvelope<?> envelope = captor.getValue();
        assertThat(envelope.getEvent()).isEqualTo("lounge.queue.update");
        WsQueueUpdatePayload payload = (WsQueueUpdatePayload) envelope.getPayload();
        assertThat(payload.getReason()).isEqualTo("manual");
        assertThat(payload.getQueue()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reorderQueueEntry_shouldBroadcastQueueUpdateWithReasonManual() {
        DjLounge lounge = createLounge(1L, 100L, LoungeStatus.LIVE);
        QueueEntry e1 = createEntry(10L, 1L, 100L, 1, 0, 100L);
        QueueEntry e2 = createEntry(20L, 1L, 200L, 2, 0, 100L);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(1L))
                .thenReturn(Flux.just(e1, e2))
                .thenReturn(Flux.just(e2, e1));
        when(queueEntryRepository.saveAll(any(Iterable.class))).thenAnswer(inv -> Flux.fromIterable(inv.getArgument(0)));

        StepVerifier.create(queueService.reorderQueueEntry(1L, 20L, 1, 100L))
                .expectNextCount(2)
                .verifyComplete();

        ArgumentCaptor<WsEventEnvelope<?>> captor = ArgumentCaptor.forClass(WsEventEnvelope.class);
        verify(loungeSessionManager).broadcast(eq(1L), captor.capture());

        WsEventEnvelope<?> envelope = captor.getValue();
        assertThat(envelope.getEvent()).isEqualTo("lounge.queue.update");
        WsQueueUpdatePayload payload = (WsQueueUpdatePayload) envelope.getPayload();
        assertThat(payload.getReason()).isEqualTo("manual");
    }
}
