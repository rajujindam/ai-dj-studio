package com.aidjstudio.ai_dj_studio.repository;

import com.aidjstudio.ai_dj_studio.entity.TrackTimelineEvent;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface TrackTimelineEventRepository extends ReactiveCrudRepository<TrackTimelineEvent, Long> {

    Flux<TrackTimelineEvent> findByTrackIdOrderByTsSecondsAsc(Long trackId);
}
