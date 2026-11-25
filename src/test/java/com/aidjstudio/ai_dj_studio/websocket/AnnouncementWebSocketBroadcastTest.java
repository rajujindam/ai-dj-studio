package com.aidjstudio.ai_dj_studio.websocket;

import com.aidjstudio.ai_dj_studio.dto.announcement.PublishSoundclashAnnouncementRequest;
import com.aidjstudio.ai_dj_studio.dto.announcement.PublishTransitionAnnouncementRequest;
import com.aidjstudio.ai_dj_studio.dto.announcement.SoundclashAnnouncementPayload;
import com.aidjstudio.ai_dj_studio.dto.announcement.TransitionAnnouncementPayload;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipantRole;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.repository.QueueEntryRepository;
import com.aidjstudio.ai_dj_studio.service.AnnouncementService;
import com.aidjstudio.ai_dj_studio.service.QueueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnnouncementWebSocketBroadcastTest {

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

    @Mock
    private AnnouncementService announcementService;

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
                null, // chatService
                null, // loungeInteractionService
                null, // soundclashService
                announcementService,
                loungeSessionManager,
                objectMapper
        );
    }

    private static class TestClientSession {
        final WebSocketSession session;
        final List<String> receivedMessages = new CopyOnWriteArrayList<>();
        final Sinks.Many<String> sink;

        TestClientSession(String sessionId, Long loungeId, Long userId, String role, LoungeSessionManager sessionManager) {
            this.session = mock(WebSocketSession.class);
            when(session.getId()).thenReturn(sessionId);
            this.sink = Sinks.many().unicast().onBackpressureBuffer();

            this.sink.asFlux().subscribe(receivedMessages::add);
            sessionManager.registerSession(loungeId, session, userId, role, sink);
        }
    }

    @Test
    @DisplayName("Verify that ONLY members of the target lounge receive the transition announcement")
    void testOnlyTargetLoungeMembersReceiveTransitionAnnouncement() throws Exception {
        Long lounge1 = 1L;
        Long lounge2 = 2L;

        // Register 2 sessions in Lounge 1
        TestClientSession l1ClientA = new TestClientSession("s-l1-a", lounge1, 101L, "HOST", loungeSessionManager);
        TestClientSession l1ClientB = new TestClientSession("s-l1-b", lounge1, 102L, "LISTENER", loungeSessionManager);

        // Register 2 sessions in Lounge 2
        TestClientSession l2ClientC = new TestClientSession("s-l2-c", lounge2, 201L, "HOST", loungeSessionManager);
        TestClientSession l2ClientD = new TestClientSession("s-l2-d", lounge2, 202L, "LISTENER", loungeSessionManager);

        // Broadcast transition announcement to Lounge 1
        TransitionAnnouncementPayload transitionPayload = TransitionAnnouncementPayload.builder()
                .announcementType("TRANSITION")
                .loungeId(lounge1)
                .currentTrackId(1L)
                .currentTrackTitle("Midnight City")
                .currentTrackArtist("M83")
                .nextTrackId(2L)
                .nextTrackTitle("Starboy")
                .nextTrackArtist("The Weeknd")
                .bpmDelta(BigDecimal.ZERO)
                .camelotRelation("SAME_KEY")
                .rankingExplanation("Harmonic match")
                .script("Mixing from Midnight City to Starboy")
                .sourceIds(List.of("track:1:timeline:101"))
                .sources(Collections.emptyList())
                .createdAt(Instant.now())
                .build();

        WsEventEnvelope<TransitionAnnouncementPayload> envelope = WsEventEnvelope.<TransitionAnnouncementPayload>builder()
                .event("announcement.transition")
                .loungeId(lounge1)
                .payload(transitionPayload)
                .ts(Instant.now())
                .build();

        loungeSessionManager.broadcast(lounge1, envelope);

        // Verify Lounge 1 clients received the announcement
        assertThat(l1ClientA.receivedMessages).hasSize(1);
        assertThat(l1ClientA.receivedMessages.get(0)).contains("\"event\":\"announcement.transition\"");
        assertThat(l1ClientA.receivedMessages.get(0)).contains("\"currentTrackTitle\":\"Midnight City\"");
        assertThat(l1ClientA.receivedMessages.get(0)).contains("\"nextTrackTitle\":\"Starboy\"");

        assertThat(l1ClientB.receivedMessages).hasSize(1);
        assertThat(l1ClientB.receivedMessages.get(0)).contains("\"event\":\"announcement.transition\"");
        assertThat(l1ClientB.receivedMessages.get(0)).contains("\"nextTrackTitle\":\"Starboy\"");

        // Verify Lounge 2 clients received NOTHING
        assertThat(l2ClientC.receivedMessages).isEmpty();
        assertThat(l2ClientD.receivedMessages).isEmpty();
    }

    @Test
    @DisplayName("Verify that ONLY members of the target lounge receive the Soundclash announcement")
    void testOnlyTargetLoungeMembersReceiveSoundclashAnnouncement() throws Exception {
        Long lounge1 = 1L;
        Long lounge2 = 2L;

        // Register clients in Lounge 1 and Lounge 2
        TestClientSession l1ClientA = new TestClientSession("s-l1-a", lounge1, 101L, "HOST", loungeSessionManager);
        TestClientSession l2ClientB = new TestClientSession("s-l2-b", lounge2, 201L, "HOST", loungeSessionManager);
        TestClientSession l2ClientC = new TestClientSession("s-l2-c", lounge2, 202L, "LISTENER", loungeSessionManager);

        // Broadcast Soundclash announcement to Lounge 2
        SoundclashAnnouncementPayload soundclashPayload = SoundclashAnnouncementPayload.builder()
                .announcementType("SOUNDCLASH")
                .loungeId(lounge2)
                .battleId(88L)
                .winningTrackId(5L)
                .winningTrackTitle("Starboy")
                .winningTrackArtist("The Weeknd")
                .winningCandidateId(10L)
                .winnerVoteCount(18)
                .totalVotes(25)
                .candidates(Collections.emptyList())
                .script("Soundclash winner decided! Starboy won with 18 votes!")
                .sourceIds(List.of("track:5:timeline:501"))
                .sources(Collections.emptyList())
                .createdAt(Instant.now())
                .build();

        WsEventEnvelope<SoundclashAnnouncementPayload> envelope = WsEventEnvelope.<SoundclashAnnouncementPayload>builder()
                .event("announcement.soundclash")
                .loungeId(lounge2)
                .payload(soundclashPayload)
                .ts(Instant.now())
                .build();

        loungeSessionManager.broadcast(lounge2, envelope);

        // Lounge 2 clients received the event
        assertThat(l2ClientB.receivedMessages).hasSize(1);
        assertThat(l2ClientB.receivedMessages.get(0)).contains("\"event\":\"announcement.soundclash\"");
        assertThat(l2ClientB.receivedMessages.get(0)).contains("\"winnerVoteCount\":18");

        assertThat(l2ClientC.receivedMessages).hasSize(1);
        assertThat(l2ClientC.receivedMessages.get(0)).contains("\"event\":\"announcement.soundclash\"");

        // Lounge 1 client received NOTHING
        assertThat(l1ClientA.receivedMessages).isEmpty();
    }

    @Test
    @DisplayName("Verify that disconnected lounge member no longer receives subsequent announcements")
    void testDisconnectedMemberDoesNotReceiveAnnouncements() {
        Long lounge1 = 1L;

        TestClientSession clientA = new TestClientSession("s-1", lounge1, 101L, "HOST", loungeSessionManager);
        TestClientSession clientB = new TestClientSession("s-2", lounge1, 102L, "LISTENER", loungeSessionManager);

        // Remove client B (disconnect)
        loungeSessionManager.removeSession(lounge1, clientB.session);

        WsEventEnvelope<TransitionAnnouncementPayload> envelope = WsEventEnvelope.<TransitionAnnouncementPayload>builder()
                .event("announcement.transition")
                .loungeId(lounge1)
                .payload(TransitionAnnouncementPayload.builder()
                        .announcementType("TRANSITION")
                        .loungeId(lounge1)
                        .currentTrackTitle("Track A")
                        .nextTrackTitle("Track B")
                        .build())
                .ts(Instant.now())
                .build();

        loungeSessionManager.broadcast(lounge1, envelope);

        // Connected client received it
        assertThat(clientA.receivedMessages).hasSize(1);
        // Disconnected client received NOTHING
        assertThat(clientB.receivedMessages).isEmpty();
    }

    @Test
    @DisplayName("Inbound WebSocket: transition announcement event triggers AnnouncementService")
    void testInboundTransitionAnnouncementTriggersService() {
        Long loungeId = 1L;
        Long userId = 100L;
        String token = "jwt-token-100";

        HandshakeInfo handshake = mock(HandshakeInfo.class);
        when(handshake.getUri()).thenReturn(URI.create("/ws/lounges/1?token=" + token));
        when(handshake.getHeaders()).thenReturn(new org.springframework.http.HttpHeaders());

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-announce-1");
        when(session.getHandshakeInfo()).thenReturn(handshake);
        lenient().when(session.textMessage(anyString())).thenAnswer(inv -> {
            WebSocketMessage msg = mock(WebSocketMessage.class);
            lenient().when(msg.getPayloadAsText()).thenReturn(inv.getArgument(0));
            return msg;
        });
        when(session.send(any())).thenReturn(Mono.empty());

        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(userId.toString());
        when(reactiveJwtDecoder.decode(token)).thenReturn(Mono.just(jwt));

        DjLounge lounge = new DjLounge();
        lounge.setId(loungeId);
        lounge.setHostUserId(userId);
        lounge.setStatus(LoungeStatus.LIVE);
        when(djLoungeRepository.findById(loungeId)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId)).thenReturn(Flux.empty());

        // Inbound message requesting transition announcement
        String inboundJson = "{\"event\":\"announcement.transition\",\"payload\":{\"candidateTrackIds\":[2,3]}}";
        WebSocketMessage incomingMsg = mock(WebSocketMessage.class);
        when(incomingMsg.getPayloadAsText()).thenReturn(inboundJson);
        when(session.receive()).thenReturn(Flux.just(incomingMsg));

        when(announcementService.publishTransitionAnnouncement(eq(loungeId), any(PublishTransitionAnnouncementRequest.class), eq(userId)))
                .thenReturn(Mono.just(TransitionAnnouncementPayload.builder().build()));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        verify(announcementService, times(1)).publishTransitionAnnouncement(
                eq(loungeId),
                any(PublishTransitionAnnouncementRequest.class),
                eq(userId)
        );
    }

    @Test
    @DisplayName("Inbound WebSocket: Soundclash announcement event triggers AnnouncementService")
    void testInboundSoundclashAnnouncementTriggersService() {
        Long loungeId = 1L;
        Long userId = 100L;
        String token = "jwt-token-100";

        HandshakeInfo handshake = mock(HandshakeInfo.class);
        when(handshake.getUri()).thenReturn(URI.create("/ws/lounges/1?token=" + token));
        when(handshake.getHeaders()).thenReturn(new org.springframework.http.HttpHeaders());

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("sess-announce-2");
        when(session.getHandshakeInfo()).thenReturn(handshake);
        lenient().when(session.textMessage(anyString())).thenAnswer(inv -> {
            WebSocketMessage msg = mock(WebSocketMessage.class);
            lenient().when(msg.getPayloadAsText()).thenReturn(inv.getArgument(0));
            return msg;
        });
        when(session.send(any())).thenReturn(Mono.empty());

        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(userId.toString());
        when(reactiveJwtDecoder.decode(token)).thenReturn(Mono.just(jwt));

        DjLounge lounge = new DjLounge();
        lounge.setId(loungeId);
        lounge.setHostUserId(userId);
        lounge.setStatus(LoungeStatus.LIVE);
        when(djLoungeRepository.findById(loungeId)).thenReturn(Mono.just(lounge));
        when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId)).thenReturn(Flux.empty());

        // Inbound message requesting soundclash announcement
        String inboundJson = "{\"event\":\"announcement.soundclash\",\"payload\":{\"battleId\":55}}";
        WebSocketMessage incomingMsg = mock(WebSocketMessage.class);
        when(incomingMsg.getPayloadAsText()).thenReturn(inboundJson);
        when(session.receive()).thenReturn(Flux.just(incomingMsg));

        when(announcementService.publishSoundclashAnnouncement(eq(loungeId), eq(55L), eq(userId)))
                .thenReturn(Mono.just(SoundclashAnnouncementPayload.builder().build()));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        verify(announcementService, times(1)).publishSoundclashAnnouncement(
                eq(loungeId),
                eq(55L),
                eq(userId)
        );
    }
}
