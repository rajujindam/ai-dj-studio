package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.track.TrackResponse;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class TrackService {

    private final TrackRepository trackRepository;

    public Flux<TrackResponse> getAllTracks() {
        return trackRepository.findAll()
                .map(TrackResponse::fromEntity);
    }

    public Mono<TrackResponse> getTrackById(Long id) {
        return trackRepository.findById(id)
                .switchIfEmpty(Mono.error(
                        new IllegalArgumentException("Track not found with id: " + id)
                ))
                .map(TrackResponse::fromEntity);
    }
}
