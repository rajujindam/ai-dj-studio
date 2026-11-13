package com.aidjstudio.ai_dj_studio.websocket;

import com.aidjstudio.ai_dj_studio.dto.queue.QueueEntryResponse;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipantRole;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.entity.QueueEntry;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.repository.QueueEntryRepository;
import com.aidjstudio.ai_dj_studio.service.QueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivestreams.Publisher;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoungeWebSocketSnapshotAndPresenceTest {

    @Mock
    private ReactiveJwtDecoder reactiveJwtDecoder;

    @Mock
    private DjLoungeRepository djLoungeRepository;

    @Mock
    private LoungeParticipantRepository loungeParticipantRepository;

    @Mock
    private QueueEntryRepository queueEntryRepository;

    @Mock
    private QueueService queueService;

    private LoungeSessionManager loungeSessionManager;
    private ObjectMapper objectMapper;
    private LoungeWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        loungeSessionManager = new LoungeSessionManager(objectMapper);

        handler = new LoungeWebSocketHandler(
                reactiveJwtDecoder,
                djLoungeRepository,
                loungeParticipantRepository,
                queueEntryRepository,
                queueService,
                loungeSessionManager,
                objectMapper
        );
    }

    private HandshakeInfo createHandshakeInfo(String uriString) {
        HandshakeInfo handshake = mock(HandshakeInfo.class);
        lenient().when(handshake.getUri()).thenReturn(URI.create(uriString));
        lenient().when(handshake.getHeaders()).thenReturn(new HttpHeaders());
        return handshake;
    }

    private WebSocketMessage createTextMessage(String payload) {
        WebSocketMessage message = mock(WebSocketMessage.class);
        lenient().when(message.getPayloadAsText()).thenReturn(payload);
        return message;
    }

    @Test
    void initialConnection_sendsAuthoritativeSnapshot_withQueueAndStatus() {
        Long loungeId = 1L;
        Long userId = 200L;
        String token = "jwt-token-200";

        HandshakeInfo handshake = createHandshakeInfo("/ws/lounges/1?token=" + token);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-200");
        when(session.getHandshakeInfo()).thenReturn(handshake);
        when(session.textMessage(anyString())).thenAnswer(inv -> createTextMessage(inv.getArgument(0)));
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.receive()).thenReturn(Flux.empty());

        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(userId.toString());
        when(reactiveJwtDecoder.decode(token)).thenReturn(Mono.just(jwt));

        DjLounge lounge = new DjLounge();
        lounge.setId(loungeId);
        lounge.setHostUserId(100L);
        lounge.setStatus(LoungeStatus.LIVE);
        lounge.setCurrentTrackId(501L);
        when(djLoungeRepository.findById(loungeId)).thenReturn(Mono.just(lounge));

        LoungeParticipant participant = new LoungeParticipant(
                10L, loungeId, userId, LoungeParticipantRole.LISTENER, LocalDateTime.now(), null
        );
        when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(loungeId, userId))
                .thenReturn(Mono.just(participant));

        QueueEntry entry1 = new QueueEntry(1L, loungeId, 502L, 1, 3, 200L);
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId))
                .thenReturn(Flux.just(entry1));
        when(queueService.toResponseDto(entry1))
                .thenReturn(QueueEntryResponse.builder().id(1L).trackId(502L).position(1).build());

        StepVerifier.create(handler.handle(session)).verifyComplete();

        ArgumentCaptor<Publisher<WebSocketMessage>> captor = ArgumentCaptor.captor();
        verify(session).send(captor.capture());

        StepVerifier.create(captor.getValue())
                .assertNext(msg -> {
                    String payload = msg.getPayloadAsText();
                    assertThat(payload).contains("connection_established");
                    assertThat(payload).contains("\"userId\":200");
                })
                .assertNext(msg -> {
                    String payload = msg.getPayloadAsText();
                    assertThat(payload).contains("lounge.snapshot");
                    assertThat(payload).contains("\"currentTrackId\":501");
                    assertThat(payload).contains("\"trackId\":502");
                    assertThat(payload).contains("\"status\":\"LIVE\"");
                    assertThat(payload).contains("\"queueFrozen\":false");
                })
                .thenCancel()
                .verify();
    }

    @Test
    void presence_whenSecondUserJoins_broadcastsJoinedPresenceEventToFirstUser() {
        Long loungeId = 1L;
        CopyOnWriteArrayList<String> session1ReceivedMessages = new CopyOnWriteArrayList<>();

        // 1. First user (Host: 100) connects
        HandshakeInfo handshake1 = createHandshakeInfo("/ws/lounges/1?token=token-100");
        WebSocketSession session1 = mock(WebSocketSession.class);
        when(session1.getId()).thenReturn("sess-host-100");
        when(session1.getHandshakeInfo()).thenReturn(handshake1);
        when(session1.textMessage(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            session1ReceivedMessages.add(text);
            return createTextMessage(text);
        });
        when(session1.send(any())).thenAnswer(inv -> {
            Publisher<WebSocketMessage> pub = inv.getArgument(0);
            Flux.from(pub).subscribe();
            return Mono.empty();
        });
        Sinks.Many<WebSocketMessage> inboundSink1 = Sinks.many().unicast().onBackpressureBuffer();
        when(session1.receive()).thenReturn(inboundSink1.asFlux());

        Jwt jwt1 = mock(Jwt.class);
        when(jwt1.getSubject()).thenReturn("100");
        when(reactiveJwtDecoder.decode("token-100")).thenReturn(Mono.just(jwt1));

        DjLounge lounge = new DjLounge();
        lounge.setId(loungeId);
        lounge.setHostUserId(100L);
        lounge.setStatus(LoungeStatus.LIVE);
        lounge.setCurrentTrackId(501L);
        when(djLoungeRepository.findById(loungeId)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId)).thenReturn(Flux.empty());

        handler.handle(session1).subscribe();

        // 2. Second user (Listener: 200) connects
        HandshakeInfo handshake2 = createHandshakeInfo("/ws/lounges/1?token=token-200");
        WebSocketSession session2 = mock(WebSocketSession.class);
        when(session2.getId()).thenReturn("sess-user-200");
        when(session2.getHandshakeInfo()).thenReturn(handshake2);
        when(session2.textMessage(anyString())).thenAnswer(inv -> createTextMessage(inv.getArgument(0)));
        when(session2.send(any())).thenReturn(Mono.empty());
        when(session2.receive()).thenReturn(Flux.empty());

        Jwt jwt2 = mock(Jwt.class);
        when(jwt2.getSubject()).thenReturn("200");
        when(reactiveJwtDecoder.decode("token-200")).thenReturn(Mono.just(jwt2));

        LoungeParticipant participant = new LoungeParticipant(
                10L, loungeId, 200L, LoungeParticipantRole.LISTENER, LocalDateTime.now(), null
        );
        when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(loungeId, 200L))
                .thenReturn(Mono.just(participant));

        handler.handle(session2).subscribe();

        // Verify session 1 received the presence join event
        assertThat(session1ReceivedMessages).anyMatch(msg ->
                msg.contains("lounge.presence.update")
                        && msg.contains("\"action\":\"joined\"")
                        && msg.contains("\"userId\":200")
        );
    }

    @Test
    void reconnect_doesNotBroadcastDuplicateJoinedEvent_andSetsReconnectAck() {
        Long loungeId = 1L;
        CopyOnWriteArrayList<String> sessionHostReceivedMessages = new CopyOnWriteArrayList<>();

        // 1. Host (100) connects
        HandshakeInfo handshakeHost = createHandshakeInfo("/ws/lounges/1?token=token-100");
        WebSocketSession sessionHost = mock(WebSocketSession.class);
        when(sessionHost.getId()).thenReturn("sess-host-100");
        when(sessionHost.getHandshakeInfo()).thenReturn(handshakeHost);
        when(sessionHost.textMessage(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            sessionHostReceivedMessages.add(text);
            return createTextMessage(text);
        });
        when(sessionHost.send(any())).thenAnswer(inv -> {
            Publisher<WebSocketMessage> pub = inv.getArgument(0);
            Flux.from(pub).subscribe();
            return Mono.empty();
        });
        Sinks.Many<WebSocketMessage> inboundSinkHost = Sinks.many().unicast().onBackpressureBuffer();
        when(sessionHost.receive()).thenReturn(inboundSinkHost.asFlux());

        Jwt jwtHost = mock(Jwt.class);
        when(jwtHost.getSubject()).thenReturn("100");
        when(reactiveJwtDecoder.decode("token-100")).thenReturn(Mono.just(jwtHost));

        DjLounge lounge = new DjLounge();
        lounge.setId(loungeId);
        lounge.setHostUserId(100L);
        lounge.setStatus(LoungeStatus.LIVE);
        when(djLoungeRepository.findById(loungeId)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId)).thenReturn(Flux.empty());

        handler.handle(sessionHost).subscribe();

        // 2. User 200 connects with session A
        HandshakeInfo handshakeA = createHandshakeInfo("/ws/lounges/1?token=token-200");
        WebSocketSession sessionA = mock(WebSocketSession.class);
        when(sessionA.getId()).thenReturn("sess-200-a");
        when(sessionA.getHandshakeInfo()).thenReturn(handshakeA);
        when(sessionA.textMessage(anyString())).thenAnswer(inv -> createTextMessage(inv.getArgument(0)));
        when(sessionA.send(any())).thenReturn(Mono.empty());
        Sinks.Many<WebSocketMessage> inboundSinkA = Sinks.many().unicast().onBackpressureBuffer();
        when(sessionA.receive()).thenReturn(inboundSinkA.asFlux());

        Jwt jwtUser = mock(Jwt.class);
        when(jwtUser.getSubject()).thenReturn("200");
        when(reactiveJwtDecoder.decode("token-200")).thenReturn(Mono.just(jwtUser));

        LoungeParticipant participant = new LoungeParticipant(
                10L, loungeId, 200L, LoungeParticipantRole.LISTENER, LocalDateTime.now(), null
        );
        when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(loungeId, 200L))
                .thenReturn(Mono.just(participant));

        handler.handle(sessionA).subscribe();

        // Clear host messages to verify subsequent reconnect behavior
        sessionHostReceivedMessages.clear();

        // 3. User 200 connects with session B (Reconnect while already active)
        HandshakeInfo handshakeB = createHandshakeInfo("/ws/lounges/1?token=token-200");
        WebSocketSession sessionB = mock(WebSocketSession.class);
        when(sessionB.getId()).thenReturn("sess-200-b");
        when(sessionB.getHandshakeInfo()).thenReturn(handshakeB);
        when(sessionB.textMessage(anyString())).thenAnswer(inv -> createTextMessage(inv.getArgument(0)));
        when(sessionB.send(any())).thenReturn(Mono.empty());
        when(sessionB.receive()).thenReturn(Flux.empty());

        handler.handle(sessionB).subscribe();

        // Verify host DID NOT receive a duplicate "joined" event for user 200
        assertThat(sessionHostReceivedMessages).noneMatch(msg ->
                msg.contains("lounge.presence.update") && msg.contains("\"action\":\"joined\"")
        );

        // Verify session B received ack indicating reconnect
        ArgumentCaptor<Publisher<WebSocketMessage>> captorB = ArgumentCaptor.captor();
        verify(sessionB).send(captorB.capture());
        StepVerifier.create(captorB.getValue())
                .assertNext(msg -> {
                    String payload = msg.getPayloadAsText();
                    assertThat(payload).contains("connection_established");
                    assertThat(payload).contains("Reconnected to lounge 1");
                })
                .assertNext(msg -> {
                    String payload = msg.getPayloadAsText();
                    assertThat(payload).contains("lounge.snapshot");
                })
                .thenCancel()
                .verify();
    }

    @Test
    void hostDisconnect_broadcastsHostDisconnected_andFreezesQueue() {
        Long loungeId = 1L;
        CopyOnWriteArrayList<String> sessionUserReceivedMessages = new CopyOnWriteArrayList<>();

        // 1. Host (100) connects
        HandshakeInfo handshakeHost = createHandshakeInfo("/ws/lounges/1?token=token-100");
        WebSocketSession sessionHost = mock(WebSocketSession.class);
        when(sessionHost.getId()).thenReturn("sess-host-100");
        when(sessionHost.getHandshakeInfo()).thenReturn(handshakeHost);
        when(sessionHost.textMessage(anyString())).thenAnswer(inv -> createTextMessage(inv.getArgument(0)));
        when(sessionHost.send(any())).thenReturn(Mono.empty());
        Sinks.Many<WebSocketMessage> hostReceiveSink = Sinks.many().unicast().onBackpressureBuffer();
        when(sessionHost.receive()).thenReturn(hostReceiveSink.asFlux());

        Jwt jwtHost = mock(Jwt.class);
        when(jwtHost.getSubject()).thenReturn("100");
        when(reactiveJwtDecoder.decode("token-100")).thenReturn(Mono.just(jwtHost));

        DjLounge lounge = new DjLounge();
        lounge.setId(loungeId);
        lounge.setHostUserId(100L);
        lounge.setStatus(LoungeStatus.LIVE);
        when(djLoungeRepository.findById(loungeId)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId)).thenReturn(Flux.empty());

        handler.handle(sessionHost).subscribe();

        // 2. Listener (200) connects
        HandshakeInfo handshakeUser = createHandshakeInfo("/ws/lounges/1?token=token-200");
        WebSocketSession sessionUser = mock(WebSocketSession.class);
        when(sessionUser.getId()).thenReturn("sess-user-200");
        when(sessionUser.getHandshakeInfo()).thenReturn(handshakeUser);
        when(sessionUser.textMessage(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            sessionUserReceivedMessages.add(text);
            return createTextMessage(text);
        });
        when(sessionUser.send(any())).thenAnswer(inv -> {
            Publisher<WebSocketMessage> pub = inv.getArgument(0);
            Flux.from(pub).subscribe();
            return Mono.empty();
        });
        Sinks.Many<WebSocketMessage> userReceiveSink = Sinks.many().unicast().onBackpressureBuffer();
        when(sessionUser.receive()).thenReturn(userReceiveSink.asFlux());

        Jwt jwtUser = mock(Jwt.class);
        when(jwtUser.getSubject()).thenReturn("200");
        when(reactiveJwtDecoder.decode("token-200")).thenReturn(Mono.just(jwtUser));

        LoungeParticipant participant = new LoungeParticipant(
                10L, loungeId, 200L, LoungeParticipantRole.LISTENER, LocalDateTime.now(), null
        );
        when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(loungeId, 200L))
                .thenReturn(Mono.just(participant));

        handler.handle(sessionUser).subscribe();

        assertThat(loungeSessionManager.isQueueFrozen(loungeId)).isFalse();

        // 3. Host disconnects (inbound finishes)
        hostReceiveSink.tryEmitComplete();

        // Verify queue is frozen and HOST_DISCONNECTED was broadcast to listener
        assertThat(loungeSessionManager.isQueueFrozen(loungeId)).isTrue();
        assertThat(sessionUserReceivedMessages).anyMatch(msg ->
                msg.contains("HOST_DISCONNECTED") && msg.contains("\"action\":\"host_disconnected\"")
        );
    }

    @Test
    void hostReconnect_broadcastsHostReconnected_andUnfreezesQueue() {
        Long loungeId = 1L;
        CopyOnWriteArrayList<String> sessionUserReceivedMessages = new CopyOnWriteArrayList<>();

        // Initially host was disconnected, queue is frozen
        loungeSessionManager.recordHostUser(loungeId, 100L);
        loungeSessionManager.setQueueFrozen(loungeId, true);

        // 1. Listener (200) connects
        HandshakeInfo handshakeUser = createHandshakeInfo("/ws/lounges/1?token=token-200");
        WebSocketSession sessionUser = mock(WebSocketSession.class);
        when(sessionUser.getId()).thenReturn("sess-user-200");
        when(sessionUser.getHandshakeInfo()).thenReturn(handshakeUser);
        when(sessionUser.textMessage(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            sessionUserReceivedMessages.add(text);
            return createTextMessage(text);
        });
        when(sessionUser.send(any())).thenAnswer(inv -> {
            Publisher<WebSocketMessage> pub = inv.getArgument(0);
            Flux.from(pub).subscribe();
            return Mono.empty();
        });
        Sinks.Many<WebSocketMessage> userReceiveSink = Sinks.many().unicast().onBackpressureBuffer();
        when(sessionUser.receive()).thenReturn(userReceiveSink.asFlux());

        Jwt jwtUser = mock(Jwt.class);
        when(jwtUser.getSubject()).thenReturn("200");
        when(reactiveJwtDecoder.decode("token-200")).thenReturn(Mono.just(jwtUser));

        DjLounge lounge = new DjLounge();
        lounge.setId(loungeId);
        lounge.setHostUserId(100L);
        lounge.setStatus(LoungeStatus.LIVE);
        when(djLoungeRepository.findById(loungeId)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId)).thenReturn(Flux.empty());

        LoungeParticipant participant = new LoungeParticipant(
                10L, loungeId, 200L, LoungeParticipantRole.LISTENER, LocalDateTime.now(), null
        );
        when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(loungeId, 200L))
                .thenReturn(Mono.just(participant));

        handler.handle(sessionUser).subscribe();

        // 2. Host (100) reconnects with active receive stream
        HandshakeInfo handshakeHost = createHandshakeInfo("/ws/lounges/1?token=token-100");
        WebSocketSession sessionHost = mock(WebSocketSession.class);
        when(sessionHost.getId()).thenReturn("sess-host-reconnect");
        when(sessionHost.getHandshakeInfo()).thenReturn(handshakeHost);
        when(sessionHost.textMessage(anyString())).thenAnswer(inv -> createTextMessage(inv.getArgument(0)));
        when(sessionHost.send(any())).thenReturn(Mono.empty());
        Sinks.Many<WebSocketMessage> hostReceiveSink = Sinks.many().unicast().onBackpressureBuffer();
        when(sessionHost.receive()).thenReturn(hostReceiveSink.asFlux());

        Jwt jwtHost = mock(Jwt.class);
        when(jwtHost.getSubject()).thenReturn("100");
        when(reactiveJwtDecoder.decode("token-100")).thenReturn(Mono.just(jwtHost));

        handler.handle(sessionHost).subscribe();

        // Queue must be unfrozen
        assertThat(loungeSessionManager.isQueueFrozen(loungeId)).isFalse();

        // Listener must have received HOST_RECONNECTED
        assertThat(sessionUserReceivedMessages).anyMatch(msg ->
                msg.contains("HOST_RECONNECTED") && msg.contains("\"action\":\"host_reconnected\"")
        );
    }

    @Test
    void listenerDisconnect_broadcastsLeftPresenceEvent() {
        Long loungeId = 1L;
        CopyOnWriteArrayList<String> sessionHostReceivedMessages = new CopyOnWriteArrayList<>();

        // 1. Host (100) connects
        HandshakeInfo handshakeHost = createHandshakeInfo("/ws/lounges/1?token=token-100");
        WebSocketSession sessionHost = mock(WebSocketSession.class);
        when(sessionHost.getId()).thenReturn("sess-host-100");
        when(sessionHost.getHandshakeInfo()).thenReturn(handshakeHost);
        when(sessionHost.textMessage(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            sessionHostReceivedMessages.add(text);
            return createTextMessage(text);
        });
        when(sessionHost.send(any())).thenAnswer(inv -> {
            Publisher<WebSocketMessage> pub = inv.getArgument(0);
            Flux.from(pub).subscribe();
            return Mono.empty();
        });
        Sinks.Many<WebSocketMessage> hostReceiveSink = Sinks.many().unicast().onBackpressureBuffer();
        when(sessionHost.receive()).thenReturn(hostReceiveSink.asFlux());

        Jwt jwtHost = mock(Jwt.class);
        when(jwtHost.getSubject()).thenReturn("100");
        when(reactiveJwtDecoder.decode("token-100")).thenReturn(Mono.just(jwtHost));

        DjLounge lounge = new DjLounge();
        lounge.setId(loungeId);
        lounge.setHostUserId(100L);
        lounge.setStatus(LoungeStatus.LIVE);
        when(djLoungeRepository.findById(loungeId)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId)).thenReturn(Flux.empty());

        handler.handle(sessionHost).subscribe();

        // 2. Listener (200) connects
        HandshakeInfo handshakeUser = createHandshakeInfo("/ws/lounges/1?token=token-200");
        WebSocketSession sessionUser = mock(WebSocketSession.class);
        when(sessionUser.getId()).thenReturn("sess-user-200");
        when(sessionUser.getHandshakeInfo()).thenReturn(handshakeUser);
        when(sessionUser.textMessage(anyString())).thenAnswer(inv -> createTextMessage(inv.getArgument(0)));
        when(sessionUser.send(any())).thenReturn(Mono.empty());
        Sinks.Many<WebSocketMessage> userReceiveSink = Sinks.many().unicast().onBackpressureBuffer();
        when(sessionUser.receive()).thenReturn(userReceiveSink.asFlux());

        Jwt jwtUser = mock(Jwt.class);
        when(jwtUser.getSubject()).thenReturn("200");
        when(reactiveJwtDecoder.decode("token-200")).thenReturn(Mono.just(jwtUser));

        LoungeParticipant participant = new LoungeParticipant(
                10L, loungeId, 200L, LoungeParticipantRole.LISTENER, LocalDateTime.now(), null
        );
        when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(loungeId, 200L))
                .thenReturn(Mono.just(participant));

        handler.handle(sessionUser).subscribe();

        // 3. Listener disconnects
        userReceiveSink.tryEmitComplete();

        // Verify host received "left" event
        assertThat(sessionHostReceivedMessages).anyMatch(msg ->
                msg.contains("lounge.presence.update")
                        && msg.contains("\"action\":\"left\"")
                        && msg.contains("\"userId\":200")
        );
    }

    @Test
    void inboundPing_repliesWithPong() {
        Long loungeId = 1L;
        CopyOnWriteArrayList<String> sessionReceivedMessages = new CopyOnWriteArrayList<>();

        HandshakeInfo handshake = createHandshakeInfo("/ws/lounges/1?token=token-200");
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-ping-200");
        when(session.getHandshakeInfo()).thenReturn(handshake);
        when(session.textMessage(anyString())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            sessionReceivedMessages.add(text);
            return createTextMessage(text);
        });
        when(session.send(any())).thenAnswer(inv -> {
            Publisher<WebSocketMessage> pub = inv.getArgument(0);
            Flux.from(pub).subscribe();
            return Mono.empty();
        });

        Sinks.Many<WebSocketMessage> inboundSink = Sinks.many().unicast().onBackpressureBuffer();
        when(session.receive()).thenReturn(inboundSink.asFlux());

        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("200");
        when(reactiveJwtDecoder.decode("token-200")).thenReturn(Mono.just(jwt));

        DjLounge lounge = new DjLounge();
        lounge.setId(loungeId);
        lounge.setHostUserId(100L);
        lounge.setStatus(LoungeStatus.LIVE);
        when(djLoungeRepository.findById(loungeId)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId)).thenReturn(Flux.empty());

        LoungeParticipant participant = new LoungeParticipant(
                10L, loungeId, 200L, LoungeParticipantRole.LISTENER, LocalDateTime.now(), null
        );
        when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(loungeId, 200L))
                .thenReturn(Mono.just(participant));

        handler.handle(session).subscribe();

        // Send ping message from client
        String pingJson = "{\"event\":\"lounge.sync.ping\",\"payload\":{\"clientSentAt\":1690000000123}}";
        WebSocketMessage incomingPing = mock(WebSocketMessage.class);
        when(incomingPing.getPayloadAsText()).thenReturn(pingJson);
        inboundSink.tryEmitNext(incomingPing);

        // Verify pong was sent
        assertThat(sessionReceivedMessages).anyMatch(msg ->
                msg.contains("lounge.sync.pong")
                        && msg.contains("1690000000123")
                        && msg.contains("serverReceivedAt")
        );
    }
}
