package com.aidjstudio.ai_dj_studio.repository;

import com.aidjstudio.ai_dj_studio.entity.ChatMessage;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

@Repository
public interface ChatMessageRepository extends ReactiveCrudRepository<ChatMessage, Long> {

    Flux<ChatMessage> findByLoungeIdOrderByCreatedAtDesc(Long loungeId);

    @Query("SELECT * FROM chat_messages WHERE lounge_id = :loungeId ORDER BY created_at DESC LIMIT :limit")
    Flux<ChatMessage> findRecentByLoungeId(Long loungeId, int limit);
}
