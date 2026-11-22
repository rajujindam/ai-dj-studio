package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipantRole;
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

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoungeModerationServiceTest {

    @Mock
    private DjLoungeRepository djLoungeRepository;

    @Mock
    private LoungeParticipantRepository loungeParticipantRepository;

    @Mock
    private LoungeSessionManager loungeSessionManager;

    private LoungeModerationService moderationService;

    private static final Long LOUNGE_ID = 1L;
    private static final Long HOST_USER_ID = 100L;
    private static final Long CO_DJ_USER_ID = 101L;
    private static final Long OTHER_CO_DJ_USER_ID = 102L;
    private static final Long LISTENER_USER_ID = 200L;
    private static final Long STRANGER_USER_ID = 999L;

    private DjLounge testLounge;
    private LoungeParticipant listenerParticipant;
    private LoungeParticipant coDjParticipant;
    private LoungeParticipant otherCoDjParticipant;

    @BeforeEach
    void setUp() {
        moderationService = new LoungeModerationService(
                djLoungeRepository,
                loungeParticipantRepository,
                loungeSessionManager
        );

        testLounge = new DjLounge();
        testLounge.setId(LOUNGE_ID);
        testLounge.setHostUserId(HOST_USER_ID);
        testLounge.setStatus(LoungeStatus.LIVE);
        testLounge.setIsLocked(false);

        listenerParticipant = new LoungeParticipant();
        listenerParticipant.setId(10L);
        listenerParticipant.setLoungeId(LOUNGE_ID);
        listenerParticipant.setUserId(LISTENER_USER_ID);
        listenerParticipant.setRole(LoungeParticipantRole.LISTENER);
        listenerParticipant.setIsMuted(false);
        listenerParticipant.setJoinedAt(LocalDateTime.now().minusHours(1));
        listenerParticipant.setLeftAt(null);

        coDjParticipant = new LoungeParticipant();
        coDjParticipant.setId(11L);
        coDjParticipant.setLoungeId(LOUNGE_ID);
        coDjParticipant.setUserId(CO_DJ_USER_ID);
        coDjParticipant.setRole(LoungeParticipantRole.CO_DJ);
        coDjParticipant.setIsMuted(false);
        coDjParticipant.setJoinedAt(LocalDateTime.now().minusHours(1));
        coDjParticipant.setLeftAt(null);

        otherCoDjParticipant = new LoungeParticipant();
        otherCoDjParticipant.setId(12L);
        otherCoDjParticipant.setLoungeId(LOUNGE_ID);
        otherCoDjParticipant.setUserId(OTHER_CO_DJ_USER_ID);
        otherCoDjParticipant.setRole(LoungeParticipantRole.CO_DJ);
        otherCoDjParticipant.setIsMuted(false);
        otherCoDjParticipant.setJoinedAt(LocalDateTime.now().minusHours(1));
        otherCoDjParticipant.setLeftAt(null);
    }

    @Nested
    @DisplayName("Mute Participant Tests")
    class MuteParticipantTests {

        @Test
        @DisplayName("Host successfully mutes a listener participant")
        void muteParticipant_hostMutingListener_success() {
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(LOUNGE_ID, LISTENER_USER_ID))
                    .thenReturn(Mono.just(listenerParticipant));
            when(loungeParticipantRepository.save(any(LoungeParticipant.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(moderationService.muteParticipant(LOUNGE_ID, LISTENER_USER_ID, true, HOST_USER_ID))
                    .expectNextMatches(p -> p.getUserId().equals(LISTENER_USER_ID) && Boolean.TRUE.equals(p.getIsMuted()))
                    .verifyComplete();

            ArgumentCaptor<WsEventEnvelope> captor = ArgumentCaptor.forClass(WsEventEnvelope.class);
            verify(loungeSessionManager).broadcast(eq(LOUNGE_ID), captor.capture());
            WsEventEnvelope envelope = captor.getValue();
            assertThat(envelope.getEvent()).isEqualTo("lounge.participant.muted");
            Map<?, ?> payload = (Map<?, ?>) envelope.getPayload();
            assertThat(payload.get("userId")).isEqualTo(LISTENER_USER_ID);
            assertThat(payload.get("muted")).isEqualTo(true);
            assertThat(payload.get("moderatorId")).isEqualTo(HOST_USER_ID);
        }

        @Test
        @DisplayName("Host successfully un-mutes a participant")
        void muteParticipant_hostUnmutingListener_success() {
            listenerParticipant.setIsMuted(true);
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(LOUNGE_ID, LISTENER_USER_ID))
                    .thenReturn(Mono.just(listenerParticipant));
            when(loungeParticipantRepository.save(any(LoungeParticipant.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(moderationService.muteParticipant(LOUNGE_ID, LISTENER_USER_ID, false, HOST_USER_ID))
                    .expectNextMatches(p -> !Boolean.TRUE.equals(p.getIsMuted()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Co-DJ successfully mutes a listener")
        void muteParticipant_coDjMutingListener_success() {
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(LOUNGE_ID, CO_DJ_USER_ID))
                    .thenReturn(Mono.just(coDjParticipant));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(LOUNGE_ID, LISTENER_USER_ID))
                    .thenReturn(Mono.just(listenerParticipant));
            when(loungeParticipantRepository.save(any(LoungeParticipant.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(moderationService.muteParticipant(LOUNGE_ID, LISTENER_USER_ID, true, CO_DJ_USER_ID))
                    .expectNextMatches(p -> Boolean.TRUE.equals(p.getIsMuted()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("Co-DJ attempting to mute another Co-DJ throws AccessDeniedException")
        void muteParticipant_coDjMutingOtherCoDj_throwsAccessDenied() {
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(LOUNGE_ID, CO_DJ_USER_ID))
                    .thenReturn(Mono.just(coDjParticipant));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(LOUNGE_ID, OTHER_CO_DJ_USER_ID))
                    .thenReturn(Mono.just(otherCoDjParticipant));

            StepVerifier.create(moderationService.muteParticipant(LOUNGE_ID, OTHER_CO_DJ_USER_ID, true, CO_DJ_USER_ID))
                    .expectErrorMatches(ex -> ex instanceof AccessDeniedException
                            && ex.getMessage().contains("Co-DJ cannot mute another Co-DJ"))
                    .verify();

            verify(loungeParticipantRepository, never()).save(any());
        }

        @Test
        @DisplayName("Attempting to mute the host throws IllegalStateException")
        void muteParticipant_targetingHost_throwsIllegalStateException() {
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));

            StepVerifier.create(moderationService.muteParticipant(LOUNGE_ID, HOST_USER_ID, true, CO_DJ_USER_ID))
                    .expectErrorMatches(ex -> ex instanceof IllegalStateException
                            && ex.getMessage().contains("Cannot mute the lounge host"))
                    .verify();
        }

        @Test
        @DisplayName("Listener attempting to mute another user throws AccessDeniedException")
        void muteParticipant_listenerCaller_throwsAccessDenied() {
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(LOUNGE_ID, LISTENER_USER_ID))
                    .thenReturn(Mono.just(listenerParticipant));

            StepVerifier.create(moderationService.muteParticipant(LOUNGE_ID, CO_DJ_USER_ID, true, LISTENER_USER_ID))
                    .expectErrorMatches(ex -> ex instanceof AccessDeniedException
                            && ex.getMessage().contains("Only the Host or Co-DJ can perform moderation actions"))
                    .verify();
        }

        @Test
        @DisplayName("Ended lounge rejects moderation actions")
        void muteParticipant_endedLounge_throwsIllegalStateException() {
            testLounge.setStatus(LoungeStatus.ENDED);
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));

            StepVerifier.create(moderationService.muteParticipant(LOUNGE_ID, LISTENER_USER_ID, true, HOST_USER_ID))
                    .expectErrorMatches(ex -> ex instanceof IllegalStateException
                            && ex.getMessage().contains("Cannot perform moderation in an ended lounge"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("Remove Participant Tests")
    class RemoveParticipantTests {

        @Test
        @DisplayName("Host successfully removes a listener, closes session and broadcasts")
        void removeParticipant_hostRemovingListener_success() {
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(LOUNGE_ID, LISTENER_USER_ID))
                    .thenReturn(Mono.just(listenerParticipant));
            when(loungeParticipantRepository.save(any(LoungeParticipant.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(moderationService.removeParticipant(LOUNGE_ID, LISTENER_USER_ID, HOST_USER_ID))
                    .expectNextMatches(p -> p.getLeftAt() != null)
                    .verifyComplete();

            verify(loungeSessionManager).disconnectUser(LOUNGE_ID, LISTENER_USER_ID, "Removed by moderator");

            ArgumentCaptor<WsEventEnvelope> captor = ArgumentCaptor.forClass(WsEventEnvelope.class);
            verify(loungeSessionManager).broadcast(eq(LOUNGE_ID), captor.capture());
            assertThat(captor.getValue().getEvent()).isEqualTo("lounge.participant.removed");
        }

        @Test
        @DisplayName("Co-DJ removing another Co-DJ throws AccessDeniedException")
        void removeParticipant_coDjRemovingOtherCoDj_throwsAccessDenied() {
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(LOUNGE_ID, CO_DJ_USER_ID))
                    .thenReturn(Mono.just(coDjParticipant));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(LOUNGE_ID, OTHER_CO_DJ_USER_ID))
                    .thenReturn(Mono.just(otherCoDjParticipant));

            StepVerifier.create(moderationService.removeParticipant(LOUNGE_ID, OTHER_CO_DJ_USER_ID, CO_DJ_USER_ID))
                    .expectErrorMatches(ex -> ex instanceof AccessDeniedException
                            && ex.getMessage().contains("Co-DJ cannot remove another Co-DJ"))
                    .verify();

            verify(loungeSessionManager, never()).disconnectUser(any(), any(), any());
        }

        @Test
        @DisplayName("Attempting to remove the host throws IllegalStateException")
        void removeParticipant_targetingHost_throwsIllegalStateException() {
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));

            StepVerifier.create(moderationService.removeParticipant(LOUNGE_ID, HOST_USER_ID, CO_DJ_USER_ID))
                    .expectErrorMatches(ex -> ex instanceof IllegalStateException
                            && ex.getMessage().contains("Cannot remove the lounge host"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("Lock Lounge Tests")
    class LockLoungeTests {

        @Test
        @DisplayName("Host successfully locks and unlocks the lounge")
        void setLoungeLocked_host_success() {
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));
            when(djLoungeRepository.save(any(DjLounge.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(moderationService.setLoungeLocked(LOUNGE_ID, true, HOST_USER_ID))
                    .expectNextMatches(lounge -> Boolean.TRUE.equals(lounge.getIsLocked()))
                    .verifyComplete();

            ArgumentCaptor<WsEventEnvelope> captor = ArgumentCaptor.forClass(WsEventEnvelope.class);
            verify(loungeSessionManager).broadcast(eq(LOUNGE_ID), captor.capture());
            assertThat(captor.getValue().getEvent()).isEqualTo("lounge.lock.updated");
        }

        @Test
        @DisplayName("Non-host caller (Co-DJ or Listener) cannot lock lounge")
        void setLoungeLocked_nonHost_throwsAccessDenied() {
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));

            StepVerifier.create(moderationService.setLoungeLocked(LOUNGE_ID, true, CO_DJ_USER_ID))
                    .expectErrorMatches(ex -> ex instanceof AccessDeniedException
                            && ex.getMessage().contains("Only the lounge host can lock or unlock the lounge"))
                    .verify();

            verify(djLoungeRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("Update Participant Role Tests")
    class UpdateParticipantRoleTests {

        @Test
        @DisplayName("Host successfully promotes Listener to CO_DJ")
        void updateParticipantRole_hostPromotingListener_success() {
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(LOUNGE_ID, LISTENER_USER_ID))
                    .thenReturn(Mono.just(listenerParticipant));
            when(loungeParticipantRepository.save(any(LoungeParticipant.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(moderationService.updateParticipantRole(LOUNGE_ID, LISTENER_USER_ID, LoungeParticipantRole.CO_DJ, HOST_USER_ID))
                    .expectNextMatches(p -> p.getRole() == LoungeParticipantRole.CO_DJ)
                    .verifyComplete();

            verify(loungeSessionManager).updateUserRole(LOUNGE_ID, LISTENER_USER_ID, "CO_DJ");

            ArgumentCaptor<WsEventEnvelope> captor = ArgumentCaptor.forClass(WsEventEnvelope.class);
            verify(loungeSessionManager).broadcast(eq(LOUNGE_ID), captor.capture());
            assertThat(captor.getValue().getEvent()).isEqualTo("lounge.role.updated");
        }

        @Test
        @DisplayName("Co-DJ cannot update participant roles")
        void updateParticipantRole_coDjCaller_throwsAccessDenied() {
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));

            StepVerifier.create(moderationService.updateParticipantRole(LOUNGE_ID, LISTENER_USER_ID, LoungeParticipantRole.CO_DJ, CO_DJ_USER_ID))
                    .expectErrorMatches(ex -> ex instanceof AccessDeniedException
                            && ex.getMessage().contains("Only the lounge host can update participant roles"))
                    .verify();

            verify(loungeParticipantRepository, never()).save(any());
        }

        @Test
        @DisplayName("Cannot change the role of the host through updateParticipantRole")
        void updateParticipantRole_targetingHost_throwsIllegalStateException() {
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));

            StepVerifier.create(moderationService.updateParticipantRole(LOUNGE_ID, HOST_USER_ID, LoungeParticipantRole.CO_DJ, HOST_USER_ID))
                    .expectErrorMatches(ex -> ex instanceof IllegalStateException
                            && ex.getMessage().contains("Cannot change the role of the lounge host"))
                    .verify();
        }
    }

    @Nested
    @DisplayName("Transfer Host Tests")
    class TransferHostTests {

        @Test
        @DisplayName("Host successfully transfers ownership: target becomes host, former host becomes CO_DJ")
        void transferHost_success() {
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(LOUNGE_ID, LISTENER_USER_ID))
                    .thenReturn(Mono.just(listenerParticipant));
            when(loungeParticipantRepository.save(any(LoungeParticipant.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
            when(djLoungeRepository.save(any(DjLounge.class)))
                    .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

            StepVerifier.create(moderationService.transferHost(LOUNGE_ID, LISTENER_USER_ID, HOST_USER_ID))
                    .expectNextMatches(lounge -> lounge.getHostUserId().equals(LISTENER_USER_ID))
                    .verifyComplete();

            // Verify target's participant row is closed
            assertThat(listenerParticipant.getLeftAt()).isNotNull();

            // Verify former host participant row was saved as CO_DJ
            ArgumentCaptor<LoungeParticipant> partCaptor = ArgumentCaptor.forClass(LoungeParticipant.class);
            verify(loungeParticipantRepository, times(2)).save(partCaptor.capture());
            LoungeParticipant formerHostPart = partCaptor.getAllValues().get(1);
            assertThat(formerHostPart.getUserId()).isEqualTo(HOST_USER_ID);
            assertThat(formerHostPart.getRole()).isEqualTo(LoungeParticipantRole.CO_DJ);
            assertThat(formerHostPart.getLeftAt()).isNull();

            // Verify session manager updates
            verify(loungeSessionManager).recordHostUser(LOUNGE_ID, LISTENER_USER_ID);
            verify(loungeSessionManager).updateUserRole(LOUNGE_ID, LISTENER_USER_ID, "HOST");
            verify(loungeSessionManager).updateUserRole(LOUNGE_ID, HOST_USER_ID, "CO_DJ");

            // Verify broadcast
            ArgumentCaptor<WsEventEnvelope> captor = ArgumentCaptor.forClass(WsEventEnvelope.class);
            verify(loungeSessionManager).broadcast(eq(LOUNGE_ID), captor.capture());
            assertThat(captor.getValue().getEvent()).isEqualTo("lounge.host.transferred");
        }

        @Test
        @DisplayName("Host cannot transfer ownership to themselves")
        void transferHost_toSelf_throwsIllegalStateException() {
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));

            StepVerifier.create(moderationService.transferHost(LOUNGE_ID, HOST_USER_ID, HOST_USER_ID))
                    .expectErrorMatches(ex -> ex instanceof IllegalStateException
                            && ex.getMessage().contains("Cannot transfer host ownership to yourself"))
                    .verify();
        }

        @Test
        @DisplayName("Co-DJ cannot transfer lounge host ownership")
        void transferHost_coDjCaller_throwsAccessDenied() {
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));

            StepVerifier.create(moderationService.transferHost(LOUNGE_ID, LISTENER_USER_ID, CO_DJ_USER_ID))
                    .expectErrorMatches(ex -> ex instanceof AccessDeniedException
                            && ex.getMessage().contains("Only the current lounge host can transfer ownership"))
                    .verify();
        }

        @Test
        @DisplayName("Transferring host to a non-active user throws IllegalArgumentException")
        void transferHost_targetNotActiveParticipant_throwsIllegalArgument() {
            when(djLoungeRepository.findById(LOUNGE_ID)).thenReturn(Mono.just(testLounge));
            when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(LOUNGE_ID, STRANGER_USER_ID))
                    .thenReturn(Mono.empty());

            StepVerifier.create(moderationService.transferHost(LOUNGE_ID, STRANGER_USER_ID, HOST_USER_ID))
                    .expectErrorMatches(ex -> ex instanceof IllegalArgumentException
                            && ex.getMessage().contains("is not an active participant"))
                    .verify();
        }
    }
}
