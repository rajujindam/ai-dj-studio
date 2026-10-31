package com.aidjstudio.ai_dj_studio.repository;

import com.aidjstudio.ai_dj_studio.entity.QueueEntry;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface QueueEntryRepository extends ReactiveCrudRepository<QueueEntry, Long> {
    Flux<QueueEntry> findByLoungeIdOrderByPositionAsc(Long loungeId);
}