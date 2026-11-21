package com.aidjstudio.ai_dj_studio.repository;

import com.aidjstudio.ai_dj_studio.entity.SoundclashCandidate;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface SoundclashCandidateRepository extends ReactiveCrudRepository<SoundclashCandidate, Long> {

    Flux<SoundclashCandidate> findByBattleIdOrderByIdAsc(Long battleId);
}
