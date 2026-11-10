package com.aidjstudio.ai_dj_studio.controller;

import com.aidjstudio.ai_dj_studio.dto.track.TrackAcousticProfileResponse;
import com.aidjstudio.ai_dj_studio.dto.track.TrackResponse;
import com.aidjstudio.ai_dj_studio.service.TrackService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/tracks")
@RequiredArgsConstructor
public class TrackController {

    private final TrackService trackService;

    @GetMapping
    public Flux<TrackResponse> getAllTracks() {
        return trackService.getAllTracks();
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<TrackResponse>> getTrackById(@PathVariable Long id) {
        return trackService.getTrackById(id)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}/acoustic-profile")
    public Mono<ResponseEntity<TrackAcousticProfileResponse>> getAcousticProfile(@PathVariable Long id) {
        return trackService.getAcousticProfileByTrackId(id)
                .map(ResponseEntity::ok);
    }
}
