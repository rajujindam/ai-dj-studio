package com.aidjstudio.ai_dj_studio.repository;

import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface DjLoungeRepository
        extends ReactiveCrudRepository<DjLounge, Long> {

    Mono<Boolean> existsByInviteCode(String inviteCode);

    Mono<DjLounge> findByInviteCode(String inviteCode);
}
