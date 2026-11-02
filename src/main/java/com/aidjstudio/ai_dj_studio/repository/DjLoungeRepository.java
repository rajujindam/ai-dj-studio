package com.aidjstudio.ai_dj_studio.repository;

import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface DjLoungeRepository
        extends ReactiveCrudRepository<DjLounge, Long> {
}