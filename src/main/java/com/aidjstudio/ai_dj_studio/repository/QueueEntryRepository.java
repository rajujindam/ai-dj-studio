package com.aidjstudio.ai_dj_studio.repository;

import com.aidjstudio.ai_dj_studio.entity.QueueEntry;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface QueueEntryRepository extends ReactiveCrudRepository<QueueEntry, Long> {

    Flux<QueueEntry> findByLoungeIdOrderByPositionAsc(Long loungeId);

    Mono<Boolean> existsByLoungeIdAndTrackId(Long loungeId, Long trackId);

    Mono<QueueEntry> findByIdAndLoungeId(Long id, Long loungeId);
}
