package com.aidjstudio.ai_dj_studio.repository;

import com.aidjstudio.ai_dj_studio.entity.SoundclashVote;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface SoundclashVoteRepository extends ReactiveCrudRepository<SoundclashVote, Long> {

    Mono<Boolean> existsByBattleIdAndUserId(Long battleId, Long userId);

    Mono<Long> countByCandidateId(Long candidateId);

    Flux<SoundclashVote> findByBattleId(Long battleId);
}
