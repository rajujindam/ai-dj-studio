package com.aidjstudio.ai_dj_studio.controller;

import com.aidjstudio.ai_dj_studio.dto.interaction.InteractionResponse;
import com.aidjstudio.ai_dj_studio.dto.interaction.RecordInteractionRequest;
import com.aidjstudio.ai_dj_studio.dto.interaction.TrackLikeRequest;
import com.aidjstudio.ai_dj_studio.service.InteractionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
@RequestMapping("/api/v1/interactions")
@RequiredArgsConstructor
public class InteractionController {

    private final InteractionService interactionService;

    @PostMapping
    public Mono<ResponseEntity<InteractionResponse>> recordInteraction(
            @Valid @RequestBody RecordInteractionRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return interactionService
                .recordInteraction(request, authenticatedUserId)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @PostMapping("/tracks/{trackId}/like")
    public Mono<ResponseEntity<InteractionResponse>> likeTrack(
            @PathVariable Long trackId,
            @RequestBody(required = false) TrackLikeRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());
        boolean liked = request == null || request.getLiked() == null || request.getLiked();
        Long loungeId = request != null ? request.getLoungeId() : null;

        return interactionService
                .toggleLike(trackId, liked, loungeId, authenticatedUserId)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/lounges/{loungeId}/co-listening")
    public Flux<InteractionResponse> recordLoungeCoListening(
            @PathVariable Long loungeId,
            @RequestParam Long trackId,
            @RequestParam(required = false) Integer playedSeconds,
            @RequestParam(required = false) Boolean completed
    ) {
        return interactionService.recordCoListening(loungeId, trackId, playedSeconds, completed);
    }

    @GetMapping("/me")
    public Flux<InteractionResponse> getMyInteractions(
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());
        return interactionService.getUserInteractions(authenticatedUserId);
    }

    @GetMapping("/lounges/{loungeId}")
    public Flux<InteractionResponse> getLoungeInteractions(
            @PathVariable Long loungeId
    ) {
        return interactionService.getLoungeInteractions(loungeId);
    }
}
