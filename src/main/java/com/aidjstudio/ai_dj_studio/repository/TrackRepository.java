package com.aidjstudio.ai_dj_studio.repository;

import com.aidjstudio.ai_dj_studio.entity.Track;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface TrackRepository extends ReactiveCrudRepository<Track, Long> {

}