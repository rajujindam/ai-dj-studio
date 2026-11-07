package com.aidjstudio.ai_dj_studio;

import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.service.LoungeLifecycleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoungeLifecycleServiceTest {

    @Mock
    private DjLoungeRepository djLoungeRepository;

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