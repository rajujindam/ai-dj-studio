package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsReactionPayload;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsTypingPayload;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoungeInteractionServiceTest {

    @Mock
    private DjLoungeRepository djLoungeRepository;

    @Mock
    private LoungeParticipantRepository loungeParticipantRepository;

    @Mock
    private LoungeSessionManager loungeSessionManager;

    private LoungeInteractionService interactionService;

    private DjLounge testLounge;
    private LoungeParticipant activeParticipant;

    @BeforeEach
    void setUp() {
        interactionService = new LoungeInteractionService(
                djLoungeRepository,
                loungeParticipantRepository,
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
    @DisplayName("Typing Indicator Tests")
    class TypingIndicatorTests {

        @Test
        @DisplayName("Participant typing=true broadcasts lounge.typing event with user identity")
        void sendTypingIndicator_participantStartsTyping_broadcastsEvent() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 200L))
                    .thenReturn(Mono.just(activeParticipant));

            StepVerifier.create(interactionService.sendTypingIndicator(10L, 200L, true))
                    .verifyComplete();

            ArgumentCaptor<WsEventEnvelope> captor = ArgumentCaptor.forClass(WsEventEnvelope.class);
            verify(loungeSessionManager).broadcast(eq(10L), captor.capture());

            WsEventEnvelope envelope = captor.getValue();
            assertThat(envelope.getEvent()).isEqualTo("lounge.typing");
            assertThat(envelope.getLoungeId()).isEqualTo(10L);
            assertThat(envelope.getPayload()).isInstanceOf(WsTypingPayload.class);

            WsTypingPayload payload = (WsTypingPayload) envelope.getPayload();
            assertThat(payload.getUserId()).isEqualTo(200L);
            assertThat(payload.isTyping()).isTrue();
        }

        @Test
        @DisplayName("Host is authorized to send typing indicators directly")
        void sendTypingIndicator_host_authorized() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));

            StepVerifier.create(interactionService.sendTypingIndicator(10L, 100L, false))
                    .verifyComplete();

            ArgumentCaptor<WsEventEnvelope> captor = ArgumentCaptor.forClass(WsEventEnvelope.class);
            verify(loungeSessionManager).broadcast(eq(10L), captor.capture());

            WsTypingPayload payload = (WsTypingPayload) captor.getValue().getPayload();
            assertThat(payload.getUserId()).isEqualTo(100L);
            assertThat(payload.isTyping()).isFalse();

            verifyNoInteractions(loungeParticipantRepository);
        }

        @Test
        @DisplayName("Non-member cannot send typing indicator (AccessDeniedException)")
        void sendTypingIndicator_nonMember_accessDenied() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 999L))
                    .thenReturn(Mono.empty());

            StepVerifier.create(interactionService.sendTypingIndicator(10L, 999L, true))
                    .expectError(AccessDeniedException.class)
                    .verify();

            verifyNoInteractions(loungeSessionManager);
        }
    }

    @Nested
    @DisplayName("Emoji Reaction Tests")
    class ReactionTests {

        @Test
        @DisplayName("Valid allowed emoji broadcasts lounge.reaction event")
        void sendReaction_allowedEmoji_broadcastsReaction() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 200L))
                    .thenReturn(Mono.just(activeParticipant));

            StepVerifier.create(interactionService.sendReaction(10L, 200L, "🔥", "TRACK", 501L))
                    .verifyComplete();

            ArgumentCaptor<WsEventEnvelope> captor = ArgumentCaptor.forClass(WsEventEnvelope.class);
            verify(loungeSessionManager).broadcast(eq(10L), captor.capture());

            WsEventEnvelope envelope = captor.getValue();
            assertThat(envelope.getEvent()).isEqualTo("lounge.reaction");
            assertThat(envelope.getPayload()).isInstanceOf(WsReactionPayload.class);

            WsReactionPayload payload = (WsReactionPayload) envelope.getPayload();
            assertThat(payload.getUserId()).isEqualTo(200L);
            assertThat(payload.getEmoji()).isEqualTo("🔥");
            assertThat(payload.getTargetType()).isEqualTo("TRACK");
            assertThat(payload.getTargetId()).isEqualTo(501L);
        }

        @Test
        @DisplayName("All 12 allowed emojis in whitelist are accepted")
        void sendReaction_allAllowedEmojis_accepted() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 200L))
                    .thenReturn(Mono.just(activeParticipant));

            for (String emoji : LoungeInteractionService.ALLOWED_EMOJIS) {
                interactionService.clearThrottling(10L, 200L); // Clear rate-limit between iterations
                StepVerifier.create(interactionService.sendReaction(10L, 200L, emoji, "LOUNGE", null))
                        .verifyComplete();
            }

            verify(loungeSessionManager, times(LoungeInteractionService.ALLOWED_EMOJIS.size()))
                    .broadcast(eq(10L), any());
        }

        @Test
        @DisplayName("Disallowed emoji is rejected with IllegalArgumentException")
        void sendReaction_disallowedEmoji_rejected() {
            StepVerifier.create(interactionService.sendReaction(10L, 200L, "🍕", "LOUNGE", null))
                    .expectErrorMatches(ex -> ex instanceof IllegalArgumentException && ex.getMessage().contains("not in the allowed reaction set"))
                    .verify();

            verifyNoInteractions(djLoungeRepository);
            verifyNoInteractions(loungeSessionManager);
        }

        @Test
        @DisplayName("Blank or null emoji is rejected")
        void sendReaction_blankEmoji_rejected() {
            StepVerifier.create(interactionService.sendReaction(10L, 200L, "   ", "LOUNGE", null))
                    .expectErrorMatches(ex -> ex instanceof IllegalArgumentException && ex.getMessage().contains("cannot be blank"))
                    .verify();
        }

        @Test
        @DisplayName("Reaction throttling / deduplication drops rapid successive reactions from same user")
        void sendReaction_rapidSuccessiveReactions_throttled() {
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 200L))
                    .thenReturn(Mono.just(activeParticipant));

            // First reaction should succeed and broadcast
            StepVerifier.create(interactionService.sendReaction(10L, 200L, "🔥", "LOUNGE", null))
                    .verifyComplete();

            // Second reaction immediately after (< 1000ms) should complete silently without broadcasting
            StepVerifier.create(interactionService.sendReaction(10L, 200L, "❤️", "LOUNGE", null))
                    .verifyComplete();

            // Verify broadcast only happened once!
            verify(loungeSessionManager, times(1)).broadcast(eq(10L), any());
        }
    }

    @Nested
    @DisplayName("Lounge State & Transience Tests")
    class LoungeStateTests {

        @Test
        @DisplayName("Reactions and typing cannot be performed in an ended lounge")
        void interaction_endedLounge_rejected() {
            testLounge.setStatus(LoungeStatus.ENDED);
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));

            StepVerifier.create(interactionService.sendReaction(10L, 200L, "🔥", "LOUNGE", null))
                    .expectErrorMatches(ex -> ex instanceof IllegalStateException && ex.getMessage().contains("Cannot interact in an ended lounge"))
                    .verify();

            StepVerifier.create(interactionService.sendTypingIndicator(10L, 200L, true))
                    .expectErrorMatches(ex -> ex instanceof IllegalStateException && ex.getMessage().contains("Cannot interact in an ended lounge"))
                    .verify();
        }

        @Test
        @DisplayName("Typing and reaction events produce zero database repository writes")
        void verifyNoDatabasePersistence() {
            // LoungeInteractionService does not even inject or reference any message/interaction repositories!
            // This guarantees by construction that typing and reactions are completely transient.
            when(djLoungeRepository.findById(10L)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 200L))
                    .thenReturn(Mono.just(activeParticipant));

            interactionService.sendTypingIndicator(10L, 200L, true).block();
            interactionService.sendReaction(10L, 200L, "🔥", "LOUNGE", null).block();

            // Only read lookups for lounge and membership are executed
            verify(djLoungeRepository, times(2)).findById(10L);
            verify(loungeParticipantRepository, times(2)).findByLoungeIdAndUserIdAndLeftAtIsNull(10L, 200L);
            verifyNoMoreInteractions(djLoungeRepository);
            verifyNoMoreInteractions(loungeParticipantRepository);
        }
    }
}
