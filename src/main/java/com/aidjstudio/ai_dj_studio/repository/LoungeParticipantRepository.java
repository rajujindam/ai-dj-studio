package com.aidjstudio.ai_dj_studio.repository;

import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface LoungeParticipantRepository
        extends ReactiveCrudRepository<LoungeParticipant, Long> {

    Mono<LoungeParticipant> findByLoungeIdAndUserIdAndLeftAtIsNull(
            Long loungeId,
            Long userId
    );

    Flux<LoungeParticipant> findByLoungeIdAndLeftAtIsNullOrderByJoinedAtAsc(
            Long loungeId
    );
}