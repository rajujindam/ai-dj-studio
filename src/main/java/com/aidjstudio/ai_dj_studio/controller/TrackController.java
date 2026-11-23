package com.aidjstudio.ai_dj_studio.controller;

import com.aidjstudio.ai_dj_studio.dto.track.RetrievedMetadataEntry;
import com.aidjstudio.ai_dj_studio.dto.track.TrackAcousticProfileResponse;
import com.aidjstudio.ai_dj_studio.dto.track.TrackQnaRequest;
import com.aidjstudio.ai_dj_studio.dto.track.TrackQnaResponse;
import com.aidjstudio.ai_dj_studio.dto.track.TrackResponse;
import com.aidjstudio.ai_dj_studio.dto.transition.TransitionDecisionRequest;
import com.aidjstudio.ai_dj_studio.dto.transition.TransitionDecisionResponse;
import com.aidjstudio.ai_dj_studio.service.TrackMetadataRetrievalService;
import com.aidjstudio.ai_dj_studio.service.TrackQnaService;
import com.aidjstudio.ai_dj_studio.service.TrackService;
import com.aidjstudio.ai_dj_studio.service.TransitionDecisionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/tracks")
@RequiredArgsConstructor
public class TrackController {

    private final TrackService trackService;
    private final TrackMetadataRetrievalService metadataRetrievalService;
    private final TrackQnaService trackQnaService;
    private final TransitionDecisionService transitionDecisionService;

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

    @GetMapping("/{id}/timeline/search")
    public Flux<RetrievedMetadataEntry> searchTimelineMetadata(
            @PathVariable Long id,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false, defaultValue = "10") Integer limit) {
        return metadataRetrievalService.retrieve(id, query, eventType, limit);
    }

    @PostMapping("/{id}/qna")
    public Mono<ResponseEntity<TrackQnaResponse>> askTrackQuestion(
            @PathVariable Long id,
            @Valid @RequestBody TrackQnaRequest request) {
        return trackQnaService.answerTrackQuestion(id, request)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{id}/transition-decision")
    public Mono<ResponseEntity<TransitionDecisionResponse>> decideTransition(
            @PathVariable Long id,
            @Valid @RequestBody TransitionDecisionRequest request) {
        return transitionDecisionService.evaluateTransition(id, request)
                .map(ResponseEntity::ok);
    }
}
