package com.aidjstudio.ai_dj_studio.repository;

import com.aidjstudio.ai_dj_studio.entity.Interaction;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface InteractionRepository extends ReactiveCrudRepository<Interaction, Long> {

    Flux<Interaction> findByUserIdOrderByCreatedAtDesc(Long userId);

    Flux<Interaction> findByTrackId(Long trackId);

    Flux<Interaction> findByLoungeIdOrderByCreatedAtDesc(Long loungeId);

    Flux<Interaction> findByUserIdAndTrackId(Long userId, Long trackId);

    Mono<Interaction> findTopByUserIdAndTrackIdOrderByCreatedAtDesc(Long userId, Long trackId);

    Mono<Boolean> existsByUserIdAndTrackIdAndLikedTrue(Long userId, Long trackId);
}
