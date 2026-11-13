package com.aidjstudio.ai_dj_studio.websocket;

import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipantRole;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
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
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoungeWebSocketHandlerTest {

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
    private WebSocketSession session;

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

        lenient().when(queueEntryRepository.findByLoungeIdOrderByPositionAsc(any()))
                .thenReturn(Flux.empty());
    }

    private HandshakeInfo createHandshakeInfo(String uriString, HttpHeaders headers) {
        HandshakeInfo handshake = mock(HandshakeInfo.class);
        lenient().when(handshake.getUri()).thenReturn(URI.create(uriString));
        lenient().when(handshake.getHeaders()).thenReturn(headers != null ? headers : new HttpHeaders());
        return handshake;
    }

    private WebSocketMessage createTextMessage(String payload) {
        WebSocketMessage message = mock(WebSocketMessage.class);
        when(message.getPayloadAsText()).thenReturn(payload);
        return message;
    }

    @Test
    void handle_validTokenInQuery_asActiveParticipant_shouldConnectAndReceiveAckAndSnapshot() {
        Long loungeId = 1L;
        Long userId = 200L;
        String token = "valid-participant-token";

        HandshakeInfo handshake = createHandshakeInfo("/ws/lounges/1?token=" + token, null);
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
        lounge.setHostUserId(100L); // Host is user 100
        lounge.setStatus(LoungeStatus.LIVE);
        when(djLoungeRepository.findById(loungeId)).thenReturn(Mono.just(lounge));

        LoungeParticipant participant = new LoungeParticipant(
                10L, loungeId, userId, LoungeParticipantRole.LISTENER, LocalDateTime.now(), null
        );
        when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(loungeId, userId))
                .thenReturn(Mono.just(participant));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        ArgumentCaptor<Publisher<WebSocketMessage>> captor = ArgumentCaptor.captor();
        verify(session).send(captor.capture());
        StepVerifier.create(captor.getValue())
                .assertNext(msg -> {
                    String payload = msg.getPayloadAsText();
                    assertThat(payload).contains("connection_established");
                    assertThat(payload).contains("\"role\":\"LISTENER\"");
                    assertThat(payload).contains("\"userId\":200");
                })
                .assertNext(msg -> {
                    String payload = msg.getPayloadAsText();
                    assertThat(payload).contains("lounge.snapshot");
                    assertThat(payload).contains("\"status\":\"LIVE\"");
                    assertThat(payload).contains("\"loungeId\":1");
                })
                .thenCancel()
                .verify();

        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    void handle_validTokenInAuthorizationHeader_asHost_shouldConnectWithHostRoleAndSnapshot() {
        Long loungeId = 1L;
        Long hostUserId = 100L;
        String token = "valid-host-token";

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        HandshakeInfo handshake = createHandshakeInfo("/ws/lounges/1", headers);

        when(session.getId()).thenReturn("sess-100");
        when(session.getHandshakeInfo()).thenReturn(handshake);
        when(session.textMessage(anyString())).thenAnswer(inv -> createTextMessage(inv.getArgument(0)));
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.receive()).thenReturn(Flux.empty());

        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(hostUserId.toString());
        when(reactiveJwtDecoder.decode(token)).thenReturn(Mono.just(jwt));

        DjLounge lounge = new DjLounge();
        lounge.setId(loungeId);
        lounge.setHostUserId(hostUserId);
        lounge.setStatus(LoungeStatus.LIVE);
        when(djLoungeRepository.findById(loungeId)).thenReturn(Mono.just(lounge));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        ArgumentCaptor<Publisher<WebSocketMessage>> captor = ArgumentCaptor.captor();
        verify(session).send(captor.capture());
        StepVerifier.create(captor.getValue())
                .assertNext(msg -> {
                    String payload = msg.getPayloadAsText();
                    assertThat(payload).contains("connection_established");
                    assertThat(payload).contains("\"role\":\"HOST\"");
                    assertThat(payload).contains("\"userId\":100");
                })
                .assertNext(msg -> {
                    String payload = msg.getPayloadAsText();
                    assertThat(payload).contains("lounge.snapshot");
                    assertThat(payload).contains("\"hostUserId\":100");
                })
                .thenCancel()
                .verify();

        verify(session, never()).close(any(CloseStatus.class));
        verify(loungeParticipantRepository, never()).findByLoungeIdAndUserIdAndLeftAtIsNull(any(), any());
    }

    @Test
    void handle_missingToken_shouldSendErrorAndCloseWithPolicyViolation() {
        HandshakeInfo handshake = createHandshakeInfo("/ws/lounges/1", null);
        when(session.getHandshakeInfo()).thenReturn(handshake);
        when(session.textMessage(anyString())).thenAnswer(inv -> createTextMessage(inv.getArgument(0)));
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        ArgumentCaptor<Publisher<WebSocketMessage>> captor = ArgumentCaptor.captor();
        verify(session).send(captor.capture());
        StepVerifier.create(captor.getValue())
                .assertNext(msg -> {
                    String payload = msg.getPayloadAsText();
                    assertThat(payload).contains("\"event\":\"error\"");
                    assertThat(payload).contains("MISSING_TOKEN");
                })
                .verifyComplete();

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void handle_invalidToken_shouldSendErrorAndClose() {
        HandshakeInfo handshake = createHandshakeInfo("/ws/lounges/1?token=bad-token", null);
        when(session.getHandshakeInfo()).thenReturn(handshake);
        when(session.textMessage(anyString())).thenAnswer(inv -> createTextMessage(inv.getArgument(0)));
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

        when(reactiveJwtDecoder.decode("bad-token")).thenReturn(Mono.error(new BadJwtException("Invalid signature")));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        ArgumentCaptor<Publisher<WebSocketMessage>> captor = ArgumentCaptor.captor();
        verify(session).send(captor.capture());
        StepVerifier.create(captor.getValue())
                .assertNext(msg -> {
                    String payload = msg.getPayloadAsText();
                    assertThat(payload).contains("\"event\":\"error\"");
                    assertThat(payload).contains("INVALID_TOKEN");
                })
                .verifyComplete();

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void handle_nonMember_shouldSendErrorAndClose() {
        Long loungeId = 1L;
        Long userId = 300L;
        String token = "valid-nonmember-token";

        HandshakeInfo handshake = createHandshakeInfo("/ws/lounges/1?token=" + token, null);
        when(session.getHandshakeInfo()).thenReturn(handshake);
        when(session.textMessage(anyString())).thenAnswer(inv -> createTextMessage(inv.getArgument(0)));
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(userId.toString());
        when(reactiveJwtDecoder.decode(token)).thenReturn(Mono.just(jwt));

        DjLounge lounge = new DjLounge();
        lounge.setId(loungeId);
        lounge.setHostUserId(100L); // Host is user 100
        lounge.setStatus(LoungeStatus.LIVE);
        when(djLoungeRepository.findById(loungeId)).thenReturn(Mono.just(lounge));

        when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(loungeId, userId))
                .thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        ArgumentCaptor<Publisher<WebSocketMessage>> captor = ArgumentCaptor.captor();
        verify(session).send(captor.capture());
        StepVerifier.create(captor.getValue())
                .assertNext(msg -> {
                    String payload = msg.getPayloadAsText();
                    assertThat(payload).contains("\"event\":\"error\"");
                    assertThat(payload).contains("NOT_A_MEMBER");
                })
                .verifyComplete();

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void handle_loungeNotFound_shouldSendErrorAndClose() {
        Long loungeId = 999L;
        Long userId = 100L;
        String token = "valid-token";

        HandshakeInfo handshake = createHandshakeInfo("/ws/lounges/999?token=" + token, null);
        when(session.getHandshakeInfo()).thenReturn(handshake);
        when(session.textMessage(anyString())).thenAnswer(inv -> createTextMessage(inv.getArgument(0)));
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(userId.toString());
        when(reactiveJwtDecoder.decode(token)).thenReturn(Mono.just(jwt));

        when(djLoungeRepository.findById(loungeId)).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        ArgumentCaptor<Publisher<WebSocketMessage>> captor = ArgumentCaptor.captor();
        verify(session).send(captor.capture());
        StepVerifier.create(captor.getValue())
                .assertNext(msg -> {
                    String payload = msg.getPayloadAsText();
                    assertThat(payload).contains("\"event\":\"error\"");
                    assertThat(payload).contains("LOUNGE_NOT_FOUND");
                })
                .verifyComplete();

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void handle_endedLounge_shouldSendErrorAndClose() {
        Long loungeId = 1L;
        Long userId = 100L;
        String token = "valid-token";

        HandshakeInfo handshake = createHandshakeInfo("/ws/lounges/1?token=" + token, null);
        when(session.getHandshakeInfo()).thenReturn(handshake);
        when(session.textMessage(anyString())).thenAnswer(inv -> createTextMessage(inv.getArgument(0)));
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(userId.toString());
        when(reactiveJwtDecoder.decode(token)).thenReturn(Mono.just(jwt));

        DjLounge lounge = new DjLounge();
        lounge.setId(loungeId);
        lounge.setHostUserId(100L);
        lounge.setStatus(LoungeStatus.ENDED);
        when(djLoungeRepository.findById(loungeId)).thenReturn(Mono.just(lounge));

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        ArgumentCaptor<Publisher<WebSocketMessage>> captor = ArgumentCaptor.captor();
        verify(session).send(captor.capture());
        StepVerifier.create(captor.getValue())
                .assertNext(msg -> {
                    String payload = msg.getPayloadAsText();
                    assertThat(payload).contains("\"event\":\"error\"");
                    assertThat(payload).contains("LOUNGE_ENDED");
                })
                .verifyComplete();

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void handle_invalidPath_shouldSendErrorAndClose() {
        HandshakeInfo handshake = createHandshakeInfo("/ws/not-a-lounge/abc", null);
        when(session.getHandshakeInfo()).thenReturn(handshake);
        when(session.textMessage(anyString())).thenAnswer(inv -> createTextMessage(inv.getArgument(0)));
        when(session.send(any())).thenReturn(Mono.empty());
        when(session.close(any(CloseStatus.class))).thenReturn(Mono.empty());

        StepVerifier.create(handler.handle(session))
                .verifyComplete();

        ArgumentCaptor<Publisher<WebSocketMessage>> captor = ArgumentCaptor.captor();
        verify(session).send(captor.capture());
        StepVerifier.create(captor.getValue())
                .assertNext(msg -> {
                    String payload = msg.getPayloadAsText();
                    assertThat(payload).contains("\"event\":\"error\"");
                    assertThat(payload).contains("INVALID_PATH");
                })
                .verifyComplete();

        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }
}
