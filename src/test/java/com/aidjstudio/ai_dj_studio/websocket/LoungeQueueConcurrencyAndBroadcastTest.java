package com.aidjstudio.ai_dj_studio.websocket;

import com.aidjstudio.ai_dj_studio.dto.queue.AddQueueEntryRequest;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsQueueUpdatePayload;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.entity.QueueEntry;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.repository.QueueEntryRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.service.LoungeQueueMutationSerializer;
import com.aidjstudio.ai_dj_studio.service.QueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoungeQueueConcurrencyAndBroadcastTest {

    @Mock
    private QueueEntryRepository queueEntryRepository;

    @Mock
    private DjLoungeRepository djLoungeRepository;

    @Mock
    private TrackRepository trackRepository;

    @Mock
    private LoungeParticipantRepository loungeParticipantRepository;

    private LoungeSessionManager loungeSessionManager;
    private LoungeQueueMutationSerializer mutationSerializer;
    private QueueService queueService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        loungeSessionManager = new LoungeSessionManager(objectMapper);
        mutationSerializer = new LoungeQueueMutationSerializer();
        queueService = new QueueService(
                queueEntryRepository,
                djLoungeRepository,
                trackRepository,
                loungeParticipantRepository,
                loungeSessionManager,
                mutationSerializer
        );
    }

    private DjLounge createLounge(Long loungeId, Long hostUserId) {
        DjLounge lounge = new DjLounge();
        lounge.setId(loungeId);
        lounge.setHostUserId(hostUserId);
        lounge.setStatus(LoungeStatus.LIVE);
        return lounge;
    }

    private Track createTrack(Long trackId) {
        Track track = new Track();
        track.setId(trackId);
        track.setTitle("Track " + trackId);
        track.setArtist("Artist " + trackId);
        track.setDurationSeconds(200);
        track.setAudioAssetUrl("https://example.com/audio.mp3");
        track.setBpm(new BigDecimal("124.00"));
        track.setCamelotKey("5A");
        track.setEnergy(new BigDecimal("0.70"));
        track.setValence(new BigDecimal("0.65"));
        return track;
    }

    private QueueEntry createEntry(Long id, Long loungeId, Long trackId, int position) {
        QueueEntry entry = new QueueEntry();
        entry.setId(id);
        entry.setLoungeId(loungeId);
        entry.setTrackId(trackId);
        entry.setPosition(position);
        entry.setVoteCount(0);
        entry.setAddedByUserId(100L);
        return entry;
    }

    private Sinks.Many<String> registerSubscriber(Long loungeId, Long userId, String sessionId) {
        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn(sessionId);
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        loungeSessionManager.registerSession(loungeId, session, userId, "LISTENER", sink);
        return sink;
    }

    @Test
    void addTrack_broadcastsQueueUpdateToAllConnectedSubscribers() {
        Long loungeId = 1L;
        DjLounge lounge = createLounge(loungeId, 100L);
        Track track = createTrack(50L);
        QueueEntry savedEntry = createEntry(10L, loungeId, 50L, 1);

        // Register 2 subscribers
        List<String> subscriber1Msgs = new CopyOnWriteArrayList<>();
        List<String> subscriber2Msgs = new CopyOnWriteArrayList<>();

        registerSubscriber(loungeId, 201L, "sess-1").asFlux().subscribe(subscriber1Msgs::add);
        registerSubscriber(loungeId, 202L, "sess-2").asFlux().subscribe(subscriber2Msgs::add);

        when(djLoungeRepository.findById(loungeId)).thenReturn(Mono.just(lounge));
        when(trackRepository.findById(50L)).thenReturn(Mono.just(track));
        when(queueEntryRepository.existsByLoungeIdAndTrackId(loungeId, 50L)).thenReturn(Mono.just(false));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId))
                .thenReturn(Flux.empty()) // for calculateNextPosition
                .thenReturn(Flux.just(savedEntry)); // for broadcast
        when(queueEntryRepository.save(any(QueueEntry.class))).thenReturn(Mono.just(savedEntry));

        StepVerifier.create(queueService.addTrackToQueue(loungeId, new AddQueueEntryRequest(50L), 100L))
                .expectNextMatches(dto -> dto.getTrackId().equals(50L) && dto.getPosition() == 1)
                .verifyComplete();

        // Both subscribers must have received the queue update broadcast
        assertThat(subscriber1Msgs).hasSize(1);
        assertThat(subscriber2Msgs).hasSize(1);

        assertThat(subscriber1Msgs.get(0)).contains("lounge.queue.update");
        assertThat(subscriber1Msgs.get(0)).contains("\"reason\":\"manual\"");
        assertThat(subscriber1Msgs.get(0)).contains("\"trackId\":50");

        assertThat(subscriber1Msgs.get(0)).isEqualTo(subscriber2Msgs.get(0));
    }

    @Test
    void concurrentMutations_multipleSubscribers_serializedAndReceiveSameFinalQueue() throws InterruptedException {
        Long loungeId = 1L;
        DjLounge lounge = createLounge(loungeId, 100L);

        // Setup 3 subscribers in the lounge
        List<String> sub1Msgs = new CopyOnWriteArrayList<>();
        List<String> sub2Msgs = new CopyOnWriteArrayList<>();
        List<String> sub3Msgs = new CopyOnWriteArrayList<>();

        registerSubscriber(loungeId, 201L, "sess-1").asFlux().subscribe(sub1Msgs::add);
        registerSubscriber(loungeId, 202L, "sess-2").asFlux().subscribe(sub2Msgs::add);
        registerSubscriber(loungeId, 203L, "sess-3").asFlux().subscribe(sub3Msgs::add);

        Track track1 = createTrack(501L);
        Track track2 = createTrack(502L);

        when(djLoungeRepository.findById(loungeId)).thenReturn(Mono.just(lounge));
        when(trackRepository.findById(501L)).thenReturn(Mono.just(track1));
        when(trackRepository.findById(502L)).thenReturn(Mono.just(track2));
        when(queueEntryRepository.existsByLoungeIdAndTrackId(eq(loungeId), anyLong())).thenReturn(Mono.just(false));

        // Shared in-memory thread-safe state simulating the database
        List<QueueEntry> dbEntries = new CopyOnWriteArrayList<>();
        AtomicInteger idGen = new AtomicInteger(1);

        // When finding ordered entries, read current simulated dbEntries with a small non-blocking delay
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId)).thenAnswer(inv ->
                Mono.delay(Duration.ofMillis(10))
                        .flatMapMany(tick -> Flux.fromIterable(new CopyOnWriteArrayList<>(dbEntries)))
        );

        // When saving, assign ID and insert into dbEntries
        when(queueEntryRepository.save(any(QueueEntry.class))).thenAnswer(inv -> {
            QueueEntry entry = inv.getArgument(0);
            return Mono.delay(Duration.ofMillis(10)).map(tick -> {
                entry.setId((long) idGen.getAndIncrement());
                dbEntries.add(entry);
                return entry;
            });
        });

        // Launch TWO concurrent mutations on the same lounge simultaneously
        Mono<Void> mutation1 = queueService.addTrackToQueue(loungeId, new AddQueueEntryRequest(501L), 100L).then();
        Mono<Void> mutation2 = queueService.addTrackToQueue(loungeId, new AddQueueEntryRequest(502L), 100L).then();

        StepVerifier.create(Flux.merge(mutation1, mutation2))
                .verifyComplete();

        // Verify that database has exactly 2 entries with serialized contiguous positions 1 and 2
        assertThat(dbEntries).hasSize(2);
        assertThat(dbEntries.get(0).getPosition()).isEqualTo(1);
        assertThat(dbEntries.get(1).getPosition()).isEqualTo(2);

        // Give any pending broadcast signals a brief moment to flush
        Thread.sleep(100);

        // All 3 subscribers must have received both updates (2 events each)
        assertThat(sub1Msgs).hasSize(2);
        assertThat(sub2Msgs).hasSize(2);
        assertThat(sub3Msgs).hasSize(2);

        // Assert that all subscribers receive the exact same final queue event
        String sub1Final = sub1Msgs.get(sub1Msgs.size() - 1);
        String sub2Final = sub2Msgs.get(sub2Msgs.size() - 1);
        String sub3Final = sub3Msgs.get(sub3Msgs.size() - 1);

        assertThat(sub1Final).isEqualTo(sub2Final);
        assertThat(sub2Final).isEqualTo(sub3Final);

        // The final event must contain both tracks and reason 'manual'
        assertThat(sub1Final).contains("lounge.queue.update");
        assertThat(sub1Final).contains("\"reason\":\"manual\"");
        assertThat(sub1Final).contains("501");
        assertThat(sub1Final).contains("502");
    }

    @Test
    void pipelineSurvivesError_andSubsequentMutationsSucceed() {
        Long loungeId = 1L;
        DjLounge lounge = createLounge(loungeId, 100L);
        Track validTrack = createTrack(501L);
        QueueEntry validEntry = createEntry(1L, loungeId, 501L, 1);

        List<String> subMsgs = new CopyOnWriteArrayList<>();
        registerSubscriber(loungeId, 201L, "sess-1").asFlux().subscribe(subMsgs::add);

        when(djLoungeRepository.findById(loungeId)).thenReturn(Mono.just(lounge));

        // First mutation: track not found (fails)
        when(trackRepository.findById(999L)).thenReturn(Mono.empty());

        // Second mutation: valid track (succeeds)
        when(trackRepository.findById(501L)).thenReturn(Mono.just(validTrack));
        when(queueEntryRepository.existsByLoungeIdAndTrackId(loungeId, 501L)).thenReturn(Mono.just(false));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId))
                .thenReturn(Flux.empty())
                .thenReturn(Flux.just(validEntry));
        when(queueEntryRepository.save(any(QueueEntry.class))).thenReturn(Mono.just(validEntry));

        // Execute failing mutation
        StepVerifier.create(queueService.addTrackToQueue(loungeId, new AddQueueEntryRequest(999L), 100L))
                .expectError(IllegalArgumentException.class)
                .verify();

        // Pipeline should still be alive for next mutation
        StepVerifier.create(queueService.addTrackToQueue(loungeId, new AddQueueEntryRequest(501L), 100L))
                .expectNextMatches(dto -> dto.getTrackId().equals(501L))
                .verifyComplete();

        // Subscriber should have received the successful mutation update
        assertThat(subMsgs).hasSize(1);
        assertThat(subMsgs.get(0)).contains("501");
    }

    @Test
    void differentLounges_executeConcurrentlyWithoutInterference() {
        Long lounge1 = 1L;
        Long lounge2 = 2L;

        DjLounge l1 = createLounge(lounge1, 100L);
        DjLounge l2 = createLounge(lounge2, 200L);

        Track track1 = createTrack(10L);
        Track track2 = createTrack(20L);

        QueueEntry e1 = createEntry(1L, lounge1, 10L, 1);
        QueueEntry e2 = createEntry(2L, lounge2, 20L, 1);

        List<String> sub1Msgs = new CopyOnWriteArrayList<>();
        List<String> sub2Msgs = new CopyOnWriteArrayList<>();

        registerSubscriber(lounge1, 100L, "s1").asFlux().subscribe(sub1Msgs::add);
        registerSubscriber(lounge2, 200L, "s2").asFlux().subscribe(sub2Msgs::add);

        when(djLoungeRepository.findById(lounge1)).thenReturn(Mono.just(l1));
        when(djLoungeRepository.findById(lounge2)).thenReturn(Mono.just(l2));
        when(trackRepository.findById(10L)).thenReturn(Mono.just(track1));
        when(trackRepository.findById(20L)).thenReturn(Mono.just(track2));
        when(queueEntryRepository.existsByLoungeIdAndTrackId(anyLong(), anyLong())).thenReturn(Mono.just(false));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(lounge1)).thenReturn(Flux.empty()).thenReturn(Flux.just(e1));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(lounge2)).thenReturn(Flux.empty()).thenReturn(Flux.just(e2));
        when(queueEntryRepository.save(any(QueueEntry.class))).thenReturn(Mono.just(e1), Mono.just(e2));

        Mono<Void> m1 = queueService.addTrackToQueue(lounge1, new AddQueueEntryRequest(10L), 100L).then();
        Mono<Void> m2 = queueService.addTrackToQueue(lounge2, new AddQueueEntryRequest(20L), 200L).then();

        StepVerifier.create(Flux.merge(m1, m2))
                .verifyComplete();

        // Lounge 1 subscriber received only lounge 1's broadcast
        assertThat(sub1Msgs).hasSize(1);
        assertThat(sub1Msgs.get(0)).contains("\"loungeId\":1");

        // Lounge 2 subscriber received only lounge 2's broadcast
        assertThat(sub2Msgs).hasSize(1);
        assertThat(sub2Msgs.get(0)).contains("\"loungeId\":2");
    }
}
