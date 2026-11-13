package com.aidjstudio.ai_dj_studio;

import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipantRole;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.service.LoungeMembershipService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoungeMembershipServiceTest {

    @Mock
    private DjLoungeRepository djLoungeRepository;

    @Mock
    private LoungeParticipantRepository loungeParticipantRepository;

    @InjectMocks
    private LoungeMembershipService loungeMembershipService;

    private DjLounge createLounge(LoungeStatus status) {
        DjLounge lounge = new DjLounge();
        lounge.setId(1L);
        lounge.setHostUserId(100L);
        lounge.setStatus(status);
        return lounge;
    }

    private LoungeParticipant createParticipant(
            Long loungeId,
            Long userId,
            LoungeParticipantRole role
    ) {
        LoungeParticipant participant = new LoungeParticipant();
        participant.setId(1L);
        participant.setLoungeId(loungeId);
        participant.setUserId(userId);
        participant.setRole(role);
        participant.setJoinedAt(LocalDateTime.now());
        participant.setLeftAt(null);
        return participant;
    }

    @Test
    void userCanJoinScheduledLounge() {
        DjLounge lounge = createLounge(LoungeStatus.SCHEDULED);

        when(djLoungeRepository.findById(1L))
                .thenReturn(Mono.just(lounge));

        when(loungeParticipantRepository
                .findByLoungeIdAndUserIdAndLeftAtIsNull(1L, 200L))
                .thenReturn(Mono.empty());

        when(loungeParticipantRepository.save(any(LoungeParticipant.class)))
                .thenAnswer(invocation ->
                        Mono.just(invocation.getArgument(0)));

        StepVerifier.create(
                        loungeMembershipService.joinLounge(1L, 200L)
                )
                .expectNextMatches(participant ->
                        participant.getLoungeId().equals(1L)
                                && participant.getUserId().equals(200L)
                                && participant.getRole()
                                == LoungeParticipantRole.LISTENER
                                && participant.getJoinedAt() != null
                                && participant.getLeftAt() == null
                )
                .verifyComplete();

        verify(loungeParticipantRepository)
                .save(any(LoungeParticipant.class));
    }

    @Test
    void userCanJoinLiveLounge() {
        DjLounge lounge = createLounge(LoungeStatus.LIVE);

        when(djLoungeRepository.findById(1L))
                .thenReturn(Mono.just(lounge));

        when(loungeParticipantRepository
                .findByLoungeIdAndUserIdAndLeftAtIsNull(1L, 200L))
                .thenReturn(Mono.empty());

        when(loungeParticipantRepository.save(any(LoungeParticipant.class)))
                .thenAnswer(invocation ->
                        Mono.just(invocation.getArgument(0)));

        StepVerifier.create(
                        loungeMembershipService.joinLounge(1L, 200L)
                )
                .expectNextMatches(participant ->
                        participant.getRole()
                                == LoungeParticipantRole.LISTENER
                )
                .verifyComplete();

        verify(loungeParticipantRepository)
                .save(any(LoungeParticipant.class));
    }

    @Test
    void userCannotJoinEndedLounge() {
        DjLounge lounge = createLounge(LoungeStatus.ENDED);

        when(djLoungeRepository.findById(1L))
                .thenReturn(Mono.just(lounge));

        StepVerifier.create(
                        loungeMembershipService.joinLounge(1L, 200L)
                )
                .expectError(IllegalStateException.class)
                .verify();

        verify(loungeParticipantRepository, never())
                .save(any(LoungeParticipant.class));
    }

    @Test
    void userCannotJoinLoungeTwice() {
        DjLounge lounge = createLounge(LoungeStatus.LIVE);

        LoungeParticipant existingParticipant = createParticipant(
                1L,
                200L,
                LoungeParticipantRole.LISTENER
        );

        when(djLoungeRepository.findById(1L))
                .thenReturn(Mono.just(lounge));

        when(loungeParticipantRepository
                .findByLoungeIdAndUserIdAndLeftAtIsNull(1L, 200L))
                .thenReturn(Mono.just(existingParticipant));

        StepVerifier.create(
                        loungeMembershipService.joinLounge(1L, 200L)
                )
                .expectError(IllegalStateException.class)
                .verify();

        verify(loungeParticipantRepository, never())
                .save(any(LoungeParticipant.class));
    }

    @Test
    void loungeHostCannotJoinAsRegularParticipant() {
        DjLounge lounge = createLounge(LoungeStatus.LIVE);

        when(djLoungeRepository.findById(1L))
                .thenReturn(Mono.just(lounge));

        StepVerifier.create(
                        loungeMembershipService.joinLounge(1L, 100L)
                )
                .expectError(IllegalStateException.class)
                .verify();

        verify(loungeParticipantRepository, never())
                .save(any(LoungeParticipant.class));
    }

    @Test
    void userCanLeaveLounge() {
        DjLounge lounge = createLounge(LoungeStatus.LIVE);

        LoungeParticipant participant = createParticipant(
                1L,
                200L,
                LoungeParticipantRole.LISTENER
        );

        when(djLoungeRepository.findById(1L))
                .thenReturn(Mono.just(lounge));

        when(loungeParticipantRepository
                .findByLoungeIdAndUserIdAndLeftAtIsNull(1L, 200L))
                .thenReturn(Mono.just(participant));

        when(loungeParticipantRepository.save(participant))
                .thenReturn(Mono.just(participant));

        StepVerifier.create(
                        loungeMembershipService.leaveLounge(1L, 200L)
                )
                .expectNextMatches(savedParticipant ->
                        savedParticipant.getLeftAt() != null
                )
                .verifyComplete();

        verify(loungeParticipantRepository).save(participant);
    }

    @Test
    void userCannotLeaveWhenNotAnActiveParticipant() {
        DjLounge lounge = createLounge(LoungeStatus.LIVE);

        when(djLoungeRepository.findById(1L))
                .thenReturn(Mono.just(lounge));

        when(loungeParticipantRepository
                .findByLoungeIdAndUserIdAndLeftAtIsNull(1L, 200L))
                .thenReturn(Mono.empty());

        StepVerifier.create(
                        loungeMembershipService.leaveLounge(1L, 200L)
                )
                .expectError(IllegalStateException.class)
                .verify();

        verify(loungeParticipantRepository, never())
                .save(any(LoungeParticipant.class));
    }

    @Test
    void userCannotJoinWhenLoungeDoesNotExist() {
        when(djLoungeRepository.findById(1L))
                .thenReturn(Mono.empty());

        StepVerifier.create(
                        loungeMembershipService.joinLounge(1L, 200L)
                )
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(loungeParticipantRepository, never())
                .save(any(LoungeParticipant.class));
    }

    @Test
    void userCannotLeaveWhenLoungeDoesNotExist() {
        when(djLoungeRepository.findById(1L))
                .thenReturn(Mono.empty());

        StepVerifier.create(
                        loungeMembershipService.leaveLounge(1L, 200L)
                )
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(loungeParticipantRepository, never())
                .save(any(LoungeParticipant.class));
    }

    @Test
    void userCanRejoinAfterLeaving() {
        DjLounge lounge = createLounge(LoungeStatus.LIVE);

        // No active membership exists; the previous membership is historical.
        when(djLoungeRepository.findById(1L))
                .thenReturn(Mono.just(lounge));

        when(loungeParticipantRepository
                .findByLoungeIdAndUserIdAndLeftAtIsNull(1L, 200L))
                .thenReturn(Mono.empty());

        when(loungeParticipantRepository.save(any(LoungeParticipant.class)))
                .thenAnswer(invocation ->
                        Mono.just(invocation.getArgument(0)));

        StepVerifier.create(
                        loungeMembershipService.joinLounge(1L, 200L)
                )
                .expectNextMatches(participant ->
                        participant.getUserId().equals(200L)
                                && participant.getRole()
                                == LoungeParticipantRole.LISTENER
                                && participant.getJoinedAt() != null
                                && participant.getLeftAt() == null
                )
                .verifyComplete();

        verify(loungeParticipantRepository)
                .save(any(LoungeParticipant.class));
    }

    @Test
    void joinLoungeIdempotent_whenAlreadyActive_returnsExistingWithoutSavingNewRecord() {
        DjLounge lounge = createLounge(LoungeStatus.LIVE);
        LoungeParticipant existing = createParticipant(1L, 200L, LoungeParticipantRole.LISTENER);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(1L, 200L))
                .thenReturn(Mono.just(existing));

        StepVerifier.create(loungeMembershipService.joinLoungeIdempotent(1L, 200L))
                .expectNextMatches(p -> p.getId().equals(1L) && p.getUserId().equals(200L))
                .verifyComplete();

        verify(loungeParticipantRepository, never()).save(any());
    }

    @Test
    void joinLoungeIdempotent_whenNewParticipant_createsAndSavesNewRecord() {
        DjLounge lounge = createLounge(LoungeStatus.LIVE);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(loungeParticipantRepository.findByLoungeIdAndUserIdAndLeftAtIsNull(1L, 200L))
                .thenReturn(Mono.empty());
        when(loungeParticipantRepository.save(any(LoungeParticipant.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(loungeMembershipService.joinLoungeIdempotent(1L, 200L))
                .expectNextMatches(p -> p.getUserId().equals(200L) && p.getRole() == LoungeParticipantRole.LISTENER)
                .verifyComplete();

        verify(loungeParticipantRepository).save(any(LoungeParticipant.class));
    }
}