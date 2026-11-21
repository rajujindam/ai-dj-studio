package com.aidjstudio.ai_dj_studio.repository;

import com.aidjstudio.ai_dj_studio.entity.SoundclashBattle;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Repository
public interface SoundclashBattleRepository extends ReactiveCrudRepository<SoundclashBattle, Long> {

    @Query("SELECT * FROM soundclash_battles WHERE lounge_id = :loungeId AND status = 'OPEN' ORDER BY opened_at DESC LIMIT 1")
    Mono<SoundclashBattle> findActiveBattleByLoungeId(Long loungeId);

    Flux<SoundclashBattle> findByLoungeIdOrderByOpenedAtDesc(Long loungeId);
}
