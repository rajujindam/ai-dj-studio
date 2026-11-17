package com.aidjstudio.ai_dj_studio.repository;

import com.aidjstudio.ai_dj_studio.entity.QueueVote;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface QueueVoteRepository extends ReactiveCrudRepository<QueueVote, Long> {

    Mono<Boolean> existsByQueueEntryIdAndUserId(Long queueEntryId, Long userId);

    Mono<Long> countByQueueEntryId(Long queueEntryId);

    Flux<QueueVote> findByLoungeId(Long loungeId);

    Mono<Void> deleteByQueueEntryId(Long queueEntryId);
}
