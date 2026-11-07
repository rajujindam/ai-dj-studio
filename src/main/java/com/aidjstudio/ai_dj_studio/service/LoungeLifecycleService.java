package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class LoungeLifecycleService {

    private final DjLoungeRepository djLoungeRepository;


    public Mono<DjLounge> startLounge(Long loungeId, Long authenticatedUserId) {
        return findLoungeAndVerifyHost(loungeId, authenticatedUserId)
                .flatMap(lounge -> {
                    if (lounge.getStatus() != LoungeStatus.SCHEDULED) {
                        return Mono.error(new IllegalStateException(
                                "Only scheduled lounges can be started"
                        ));
                    }

                    lounge.setStatus(LoungeStatus.LIVE);
                    return djLoungeRepository.save(lounge);
                });
    }

    public Mono<DjLounge> endLounge(Long loungeId, Long authenticatedUserId) {
        return findLoungeAndVerifyHost(loungeId, authenticatedUserId)
                .flatMap(lounge -> {
                    if (lounge.getStatus() == LoungeStatus.ENDED) {
                        return Mono.error(new IllegalStateException(
                                "Lounge is already ended"
                        ));
                    }

                    lounge.setStatus(LoungeStatus.ENDED);
                    return djLoungeRepository.save(lounge);
                });
    }

    private Mono<DjLounge> findLoungeAndVerifyHost(
            Long loungeId,
            Long authenticatedUserId
    ) {
        return djLoungeRepository.findById(loungeId)
                .switchIfEmpty(Mono.error(
                        new IllegalArgumentException(
                                "Lounge not found: " + loungeId
                        )
                ))
                .flatMap(lounge -> {
                    if (!lounge.getHostUserId().equals(authenticatedUserId)) {
                        return Mono.error(new AccessDeniedException(
                                "Only the lounge host can perform this action"
                        ));
                    }

                    return Mono.just(lounge);
                });
    }
}