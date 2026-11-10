package com.aidjstudio.ai_dj_studio.controller;

import com.aidjstudio.ai_dj_studio.dto.lounge.CreateLoungeRequest;
import com.aidjstudio.ai_dj_studio.dto.lounge.CreateLoungeResponse;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.service.LoungeLifecycleService;
import com.aidjstudio.ai_dj_studio.service.LoungeMembershipService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/lounges")
@RequiredArgsConstructor
public class LoungeController {

    private final LoungeLifecycleService loungeLifecycleService;
    private final LoungeMembershipService loungeMembershipService;

    @PostMapping
    public Mono<ResponseEntity<CreateLoungeResponse>> createLounge(
            @Valid @RequestBody CreateLoungeRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return loungeLifecycleService
                .createLounge(request, authenticatedUserId)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @PostMapping("/{loungeId}/start")
    public Mono<ResponseEntity<DjLounge>> startLounge(
            @PathVariable Long loungeId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return loungeLifecycleService
                .startLounge(loungeId, authenticatedUserId)
                .map(lounge -> ResponseEntity.ok(lounge));
    }

    @PostMapping("/{loungeId}/end")
    public Mono<ResponseEntity<DjLounge>> endLounge(
            @PathVariable Long loungeId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return loungeLifecycleService
                .endLounge(loungeId, authenticatedUserId)
                .map(lounge -> ResponseEntity.ok(lounge));
    }

    @PostMapping("/{loungeId}/join")
    public Mono<ResponseEntity<LoungeParticipant>> joinLounge(
            @PathVariable Long loungeId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return loungeMembershipService
                .joinLounge(loungeId, authenticatedUserId)
                .map(participant -> ResponseEntity.ok(participant));
    }

    @PostMapping("/{loungeId}/leave")
    public Mono<ResponseEntity<LoungeParticipant>> leaveLounge(
            @PathVariable Long loungeId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return loungeMembershipService
                .leaveLounge(loungeId, authenticatedUserId)
                .map(participant -> ResponseEntity.ok(participant));
    }

    @GetMapping("/{loungeId}/participants")
    public Flux<LoungeParticipant> getActiveParticipants(
            @PathVariable Long loungeId
    ) {
        return loungeMembershipService.getActiveParticipants(loungeId);
    }
}
