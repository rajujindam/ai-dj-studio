package com.aidjstudio.ai_dj_studio;

import com.aidjstudio.ai_dj_studio.dto.lounge.CreateLoungeRequest;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.service.LoungeLifecycleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoungeLifecycleServiceTest {

    @Mock
    private DjLoungeRepository djLoungeRepository;

    @Mock
    private TrackRepository trackRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private LoungeLifecycleService loungeLifecycleService;

    private DjLounge createLounge(LoungeStatus status) {
        DjLounge lounge = new DjLounge();
        lounge.setId(1L);
        lounge.setHostUserId(100L);
        lounge.setStatus(status);
        return lounge;
    }

    @Test
    void createLounge_whenValid_shouldCreateAndReturnResponse() {
        CreateLoungeRequest request = CreateLoungeRequest.builder()
                .seedTrackId(2001L)
                .vibeProfile("High-Energy EDM")
                .maxParticipants(40)
                .bpmTolerancePct(new BigDecimal("8.00"))
                .soundclashEnabled(true)
                .aiVerbosity("normal")
                .build();

        Track seedTrack = new Track();
        seedTrack.setId(2001L);
        seedTrack.setTitle("Night Drive");

        when(trackRepository.findById(2001L)).thenReturn(Mono.just(seedTrack));
        when(djLoungeRepository.existsByInviteCode(any())).thenReturn(Mono.just(false));
        when(djLoungeRepository.save(any(DjLounge.class))).thenAnswer(invocation -> {
            DjLounge saved = invocation.getArgument(0);
            saved.setId(77120L);
            return Mono.just(saved);
        });

        StepVerifier.create(loungeLifecycleService.createLounge(request, 100L))
                .assertNext(response -> {
                    assertEquals(77120L, response.getLoungeId());
                    assertNotNull(response.getInviteCode());
                    assertTrue(response.getInviteCode().startsWith("SP-"));
                    assertEquals(LoungeStatus.SCHEDULED, response.getStatus());
                    assertEquals(100L, response.getHostUserId());
                    assertNotNull(response.getCreatedAt());
                })
                .verifyComplete();

        verify(trackRepository).findById(2001L);
        verify(djLoungeRepository).save(any(DjLounge.class));
    }

    @Test
    void createLounge_whenSeedTrackNotFound_shouldThrowIllegalArgumentException() {
        CreateLoungeRequest request = CreateLoungeRequest.builder()
                .seedTrackId(9999L)
                .build();

        when(trackRepository.findById(9999L)).thenReturn(Mono.empty());

        StepVerifier.create(loungeLifecycleService.createLounge(request, 100L))
                .expectErrorMatches(throwable ->
                        throwable instanceof IllegalArgumentException
                                && throwable.getMessage().contains("Seed track not found with id: 9999")
                )
                .verify();

        verify(djLoungeRepository, never()).save(any(DjLounge.class));
    }

    @Test
    void hostCanStartScheduledLounge() {
        DjLounge lounge = createLounge(LoungeStatus.SCHEDULED);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(djLoungeRepository.save(lounge)).thenReturn(Mono.just(lounge));

        StepVerifier.create(loungeLifecycleService.startLounge(1L, 100L))
                .expectNextMatches(savedLounge ->
                        savedLounge.getStatus() == LoungeStatus.LIVE)
                .verifyComplete();

        verify(djLoungeRepository).save(lounge);
    }

    @Test
    void hostCanEndLiveLounge() {
        DjLounge lounge = createLounge(LoungeStatus.LIVE);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(djLoungeRepository.save(lounge)).thenReturn(Mono.just(lounge));

        StepVerifier.create(loungeLifecycleService.endLounge(1L, 100L))
                .expectNextMatches(savedLounge ->
                        savedLounge.getStatus() == LoungeStatus.ENDED)
                .verifyComplete();

        verify(djLoungeRepository).save(lounge);
    }

    @Test
    void hostCanEndScheduledLounge() {
        DjLounge lounge = createLounge(LoungeStatus.SCHEDULED);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));
        when(djLoungeRepository.save(lounge)).thenReturn(Mono.just(lounge));

        StepVerifier.create(loungeLifecycleService.endLounge(1L, 100L))
                .expectNextMatches(savedLounge ->
                        savedLounge.getStatus() == LoungeStatus.ENDED)
                .verifyComplete();

        verify(djLoungeRepository).save(lounge);
    }

    @Test
    void nonHostCannotStartLounge() {
        DjLounge lounge = createLounge(LoungeStatus.SCHEDULED);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));

        StepVerifier.create(loungeLifecycleService.startLounge(1L, 200L))
                .expectError(AccessDeniedException.class)
                .verify();

        verify(djLoungeRepository, never()).save(any(DjLounge.class));
    }

    @Test
    void nonHostCannotEndLounge() {
        DjLounge lounge = createLounge(LoungeStatus.LIVE);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));

        StepVerifier.create(loungeLifecycleService.endLounge(1L, 200L))
                .expectError(AccessDeniedException.class)
                .verify();

        verify(djLoungeRepository, never()).save(any(DjLounge.class));
    }

    @Test
    void cannotStartLoungeThatIsNotScheduled() {
        DjLounge lounge = createLounge(LoungeStatus.LIVE);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));

        StepVerifier.create(loungeLifecycleService.startLounge(1L, 100L))
                .expectError(IllegalStateException.class)
                .verify();

        verify(djLoungeRepository, never()).save(any(DjLounge.class));
    }

    @Test
    void cannotEndAlreadyEndedLounge() {
        DjLounge lounge = createLounge(LoungeStatus.ENDED);

        when(djLoungeRepository.findById(1L)).thenReturn(Mono.just(lounge));

        StepVerifier.create(loungeLifecycleService.endLounge(1L, 100L))
                .expectError(IllegalStateException.class)
                .verify();

        verify(djLoungeRepository, never()).save(any(DjLounge.class));
    }

    @Test
    void loungeNotFoundWhenStarting() {
        when(djLoungeRepository.findById(1L)).thenReturn(Mono.empty());

        StepVerifier.create(loungeLifecycleService.startLounge(1L, 100L))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(djLoungeRepository, never()).save(any(DjLounge.class));
    }

    @Test
    void loungeNotFoundWhenEnding() {
        when(djLoungeRepository.findById(1L)).thenReturn(Mono.empty());

        StepVerifier.create(loungeLifecycleService.endLounge(1L, 100L))
                .expectError(IllegalArgumentException.class)
                .verify();

        verify(djLoungeRepository, never()).save(any(DjLounge.class));
    }
}
