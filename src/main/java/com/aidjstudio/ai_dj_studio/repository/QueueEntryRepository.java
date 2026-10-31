package com.aidjstudio.ai_dj_studio.repository;

import com.aidjstudio.ai_dj_studio.entity.QueueEntry;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface QueueEntryRepository extends ReactiveCrudRepository<QueueEntry, Long> {

}