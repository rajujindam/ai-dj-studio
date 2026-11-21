package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.chat.ChatMessageResponse;
import com.aidjstudio.ai_dj_studio.dto.chat.SendChatMessageRequest;
import com.aidjstudio.ai_dj_studio.dto.sentiment.MessageSentiment;
import com.aidjstudio.ai_dj_studio.dto.sentiment.RoomSentimentSnapshot;
import com.aidjstudio.ai_dj_studio.dto.sentiment.SentimentBucket;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import com.aidjstudio.ai_dj_studio.entity.ChatMessage;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.repository.ChatMessageRepository;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.service.sentiment.RollingWindowSentimentService;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private DjLoungeRepository djLoungeRepository;

    @Mock
    private LoungeParticipantRepository loungeParticipantRepository;

    @Mock
    private RollingWindowSentimentService rollingWindowSentimentService;

    @Mock
    private LoungeSessionManager loungeSessionManager;

    private ChatService chatService;

    private DjLounge testLounge;
    private LoungeParticipant activeParticipant;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(
                chatMessageRepository,
                djLoungeRepository,
                loungeParticipantRepository,
                rollingWindowSentimentService,
                loungeSessionManager
        );

        testLounge = new DjLounge();
        testLounge.setId(10L);
        testLounge.setHostUserId(100L);
        testLounge.setStatus(LoungeStatus.LIVE);

        activeParticipant = new LoungeParticipant();
        activeParticipant.setId(5L);
        activeParticipant.setLoungeId(10L);
        activeParticipant.setUserId(200L);
        activeParticipant.setLeftAt(null);
    }

    @Nested
    @DisplayName("Send Message & Persistence Tests")
    class SendMessageTests {

        @Test
        @DisplayName("Should successfully send, persist, score, and broadcast chat message")
        void sendMessage_successful_persistsAndBroadcasts() {
            SendChatMessageRequest request = SendChatMessageRequest.builder()
                    .body("This transition is absolute fire! 🔥")
                    .msgType("TEXT")
                    .build();

            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 200L))
                    .thenReturn(Mono.just(activeParticipant));

            MessageSentiment sentiment = MessageSentiment.builder()
                    .score(0.85)
                    .bucket(SentimentBucket.POSITIVE)
                    .build();
            when(rollingWindowSentimentService.recordMessage(eq(10L), anyString(), eq(200L), any(Instant.class)))
                    .thenReturn(sentiment);

            RoomSentimentSnapshot snapshot = RoomSentimentSnapshot.builder()
                    .loungeId(10L)
                    .roomScore(0.85)
                    .build();
            when(rollingWindowSentimentService.getRoomSentiment(10L)).thenReturn(snapshot);

            when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
                ChatMessage saved = invocation.getArgument(0);
                saved.setId(101L);
                return Mono.just(saved);
            });

            StepVerifier.create(chatService.sendMessage(10L, request, 200L))
                    .assertNext(response -> {
                        assertThat(response.getId()).isEqualTo(101L);
                        assertThat(response.getLoungeId()).isEqualTo(10L);
                        assertThat(response.getUserId()).isEqualTo(200L);
                        assertThat(response.getBody()).isEqualTo("This transition is absolute fire! 🔥");
                        assertThat(response.getSentimentScore()).isEqualByComparingTo(new BigDecimal("0.850"));
                        assertThat(response.getMsgType()).isEqualTo("TEXT");
                    })
                    .verifyComplete();

            // Verify persistence
            ArgumentCaptor<ChatMessage> msgCaptor = ArgumentCaptor.forClass(ChatMessage.class);
            verify(chatMessageRepository).save(msgCaptor.capture());
            ChatMessage savedEntity = msgCaptor.getValue();
            assertThat(savedEntity.getUserId()).isEqualTo(200L);
            assertThat(savedEntity.getBody()).isEqualTo("This transition is absolute fire! 🔥");

            // Verify WebSocket broadcasts (both chat.message and sentiment.update)
            ArgumentCaptor<WsEventEnvelope> envelopeCaptor = ArgumentCaptor.forClass(WsEventEnvelope.class);
            verify(loungeSessionManager, times(2)).broadcast(eq(10L), envelopeCaptor.capture());

            List<WsEventEnvelope> sentEnvelopes = envelopeCaptor.getAllValues();
            assertThat(sentEnvelopes).hasSize(2);
            assertThat(sentEnvelopes.get(0).getEvent()).isEqualTo("chat.message");
            assertThat(sentEnvelopes.get(1).getEvent()).isEqualTo("sentiment.update");
        }

        @Test
        @DisplayName("Should sanitize HTML/XSS content safely before persistence and broadcast")
        void sendMessage_sanitizesHtmlContent() {
            SendChatMessageRequest request = SendChatMessageRequest.builder()
                    .body("<script>alert('pwned')</script>Hello <b>World</b>")
                    .build();

            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            // User 100 is host, auto-authorized
            when(rollingWindowSentimentService.recordMessage(eq(10L), anyString(), eq(100L), any(Instant.class)))
                    .thenReturn(MessageSentiment.builder().score(0.0).bucket(SentimentBucket.NEUTRAL).build());
            when(rollingWindowSentimentService.getRoomSentiment(10L))
                    .thenReturn(RoomSentimentSnapshot.builder().loungeId(10L).roomScore(0.0).build());

            when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
                ChatMessage saved = invocation.getArgument(0);
                saved.setId(102L);
                return Mono.just(saved);
            });

            StepVerifier.create(chatService.sendMessage(10L, request, 100L))
                    .assertNext(response -> {
                        assertThat(response.getBody()).doesNotContain("<script>");
                        assertThat(response.getBody()).contains("&lt;script&gt;alert(&#39;pwned&#39;)&lt;/script&gt;Hello &lt;b&gt;World&lt;/b&gt;");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Should reject blank or null message body")
        void sendMessage_blankBody_throwsException() {
            SendChatMessageRequest request = SendChatMessageRequest.builder().body("   ").build();

            StepVerifier.create(chatService.sendMessage(10L, request, 200L))
                    .expectErrorMatches(ex -> ex instanceof IllegalArgumentException && ex.getMessage().contains("cannot be blank"))
                    .verify();

            verifyNoInteractions(chatMessageRepository);
        }

        @Test
        @DisplayName("Should reject oversized message body (> 500 characters)")
        void sendMessage_oversizedBody_throwsException() {
            String longText = "a".repeat(501);
            SendChatMessageRequest request = SendChatMessageRequest.builder().body(longText).build();

            StepVerifier.create(chatService.sendMessage(10L, request, 200L))
                    .expectErrorMatches(ex -> ex instanceof IllegalArgumentException && ex.getMessage().contains("exceeds maximum length"))
                    .verify();

            verifyNoInteractions(chatMessageRepository);
        }
    }

    @Nested
    @DisplayName("Authorization & Lounge State Tests")
    class AuthorizationTests {

        @Test
        @DisplayName("Host is automatically authorized to chat even without LoungeParticipant record")
        void sendMessage_hostUser_authorized() {
            SendChatMessageRequest request = SendChatMessageRequest.builder().body("Welcome to the lounge!").build();
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(rollingWindowSentimentService.recordMessage(eq(10L), anyString(), eq(100L), any(Instant.class)))
                    .thenReturn(MessageSentiment.builder().score(0.5).bucket(SentimentBucket.POSITIVE).build());
            when(rollingWindowSentimentService.getRoomSentiment(10L))
                    .thenReturn(RoomSentimentSnapshot.builder().loungeId(10L).roomScore(0.5).build());

            when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
                ChatMessage saved = invocation.getArgument(0);
                saved.setId(103L);
                return Mono.just(saved);
            });

            StepVerifier.create(chatService.sendMessage(10L, request, 100L))
                    .assertNext(response -> assertThat(response.getUserId()).isEqualTo(100L))
                    .verifyComplete();

            verifyNoInteractions(loungeParticipantRepository);
        }

        @Test
        @DisplayName("Non-member participant is rejected with AccessDeniedException")
        void sendMessage_nonMember_accessDenied() {
            SendChatMessageRequest request = SendChatMessageRequest.builder().body("Let me in!").build();
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 999L))
                    .thenReturn(Mono.empty());

            StepVerifier.create(chatService.sendMessage(10L, request, 999L))
                    .expectError(AccessDeniedException.class)
                    .verify();

            verifyNoInteractions(chatMessageRepository);
        }

        @Test
        @DisplayName("Participant cannot chat in an ended lounge")
        void sendMessage_endedLounge_throwsIllegalStateException() {
            testLounge.setStatus(LoungeStatus.ENDED);
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));

            SendChatMessageRequest request = SendChatMessageRequest.builder().body("Still here?").build();

            StepVerifier.create(chatService.sendMessage(10L, request, 200L))
                    .expectErrorMatches(ex -> ex instanceof IllegalStateException && ex.getMessage().contains("Cannot access chat of an ended lounge"))
                    .verify();

            verifyNoInteractions(chatMessageRepository);
        }
    }

    @Nested
    @DisplayName("Chat History Tests")
    class ChatHistoryTests {

        @Test
        @DisplayName("Should return recent messages in correct descending timestamp order")
        void getChatHistory_successful() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 200L))
                    .thenReturn(Mono.just(activeParticipant));

            ChatMessage msg1 = ChatMessage.builder()
                    .id(1L)
                    .loungeId(10L)
                    .userId(200L)
                    .body("First message")
                    .createdAt(LocalDateTime.now().minusMinutes(2))
                    .build();
            ChatMessage msg2 = ChatMessage.builder()
                    .id(2L)
                    .loungeId(10L)
                    .userId(100L)
                    .body("Second message")
                    .createdAt(LocalDateTime.now().minusMinutes(1))
                    .build();

            when(chatMessageRepository.findRecentByLoungeId(10L, 50))
                    .thenReturn(Flux.just(msg2, msg1)); // Newest first

            StepVerifier.create(chatService.getChatHistory(10L, 50, 200L))
                    .assertNext(dto -> {
                        assertThat(dto.getId()).isEqualTo(2L);
                        assertThat(dto.getBody()).isEqualTo("Second message");
                    })
                    .assertNext(dto -> {
                        assertThat(dto.getId()).isEqualTo(1L);
                        assertThat(dto.getBody()).isEqualTo("First message");
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Chat history clamps negative limit to default (50) and caps max at 100")
        void getChatHistory_limitClamping() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 200L))
                    .thenReturn(Mono.just(activeParticipant));

            when(chatMessageRepository.findRecentByLoungeId(10L, 100)).thenReturn(Flux.empty());

            StepVerifier.create(chatService.getChatHistory(10L, 500, 200L))
                    .verifyComplete();

            verify(chatMessageRepository).findRecentByLoungeId(10L, 100);
        }
    }
}
