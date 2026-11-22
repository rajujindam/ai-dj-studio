package com.aidjstudio.ai_dj_studio.controller;

import com.aidjstudio.ai_dj_studio.dto.chat.ChatMessageResponse;
import com.aidjstudio.ai_dj_studio.dto.chat.SendChatMessageRequest;
import com.aidjstudio.ai_dj_studio.dto.lounge.CreateLoungeRequest;
import com.aidjstudio.ai_dj_studio.dto.lounge.CreateLoungeResponse;
import com.aidjstudio.ai_dj_studio.dto.moderation.LockLoungeRequest;
import com.aidjstudio.ai_dj_studio.dto.moderation.MuteParticipantRequest;
import com.aidjstudio.ai_dj_studio.dto.moderation.TransferHostRequest;
import com.aidjstudio.ai_dj_studio.dto.moderation.UpdateRoleRequest;
import com.aidjstudio.ai_dj_studio.dto.queue.AddQueueEntryRequest;
import com.aidjstudio.ai_dj_studio.dto.queue.LoungePlaybackStateResponse;
import com.aidjstudio.ai_dj_studio.dto.queue.QueueEntryResponse;
import com.aidjstudio.ai_dj_studio.dto.queue.ReorderQueueEntryRequest;
import com.aidjstudio.ai_dj_studio.dto.soundclash.OpenSoundclashBattleRequest;
import com.aidjstudio.ai_dj_studio.dto.soundclash.SoundclashBattleResponse;
import com.aidjstudio.ai_dj_studio.dto.soundclash.VoteSoundclashRequest;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.service.ChatService;
import com.aidjstudio.ai_dj_studio.service.LoungeLifecycleService;
import com.aidjstudio.ai_dj_studio.service.LoungeMembershipService;
import com.aidjstudio.ai_dj_studio.service.LoungeModerationService;
import com.aidjstudio.ai_dj_studio.service.PlaybackService;
import com.aidjstudio.ai_dj_studio.service.QueueService;
import com.aidjstudio.ai_dj_studio.service.SoundclashService;
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
    private final QueueService queueService;
    private final PlaybackService playbackService;
    private final ChatService chatService;
    private final SoundclashService soundclashService;
    private final LoungeModerationService loungeModerationService;

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

    @GetMapping("/{loungeId}/queue")
    public Flux<QueueEntryResponse> getQueue(
            @PathVariable Long loungeId
    ) {
        return queueService.getQueue(loungeId);
    }

    @PostMapping("/{loungeId}/queue")
    public Mono<ResponseEntity<QueueEntryResponse>> addTrackToQueue(
            @PathVariable Long loungeId,
            @Valid @RequestBody AddQueueEntryRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return queueService
                .addTrackToQueue(loungeId, request, authenticatedUserId)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @DeleteMapping("/{loungeId}/queue/{queueEntryId}")
    public Mono<ResponseEntity<Void>> removeQueueEntry(
            @PathVariable Long loungeId,
            @PathVariable Long queueEntryId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return queueService
                .removeQueueEntry(loungeId, queueEntryId, authenticatedUserId)
                .thenReturn(ResponseEntity.noContent().<Void>build());
    }

    @PutMapping("/{loungeId}/queue/{queueEntryId}/reorder")
    public Flux<QueueEntryResponse> reorderQueueEntry(
            @PathVariable Long loungeId,
            @PathVariable Long queueEntryId,
            @Valid @RequestBody ReorderQueueEntryRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return queueService.reorderQueueEntry(
                loungeId,
                queueEntryId,
                request.getTargetPosition(),
                authenticatedUserId
        );
    }

    @PostMapping("/{loungeId}/queue/{queueEntryId}/move-to-top")
    public Flux<QueueEntryResponse> moveQueueEntryToTop(
            @PathVariable Long loungeId,
            @PathVariable Long queueEntryId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return queueService.moveQueueEntryToTop(
                loungeId,
                queueEntryId,
                authenticatedUserId
        );
    }

    @PostMapping("/{loungeId}/queue/{queueEntryId}/vote")
    public Mono<ResponseEntity<QueueEntryResponse>> voteQueueEntry(
            @PathVariable Long loungeId,
            @PathVariable Long queueEntryId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return queueService
                .voteQueueEntry(loungeId, queueEntryId, authenticatedUserId)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{loungeId}/playback")
    public Mono<ResponseEntity<LoungePlaybackStateResponse>> getPlaybackState(
            @PathVariable Long loungeId
    ) {
        return playbackService
                .getPlaybackState(loungeId)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{loungeId}/playback/next")
    public Mono<ResponseEntity<LoungePlaybackStateResponse>> advanceToNextTrack(
            @PathVariable Long loungeId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return playbackService
                .advanceToNextTrack(loungeId, authenticatedUserId)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{loungeId}/playback/promote/{queueEntryId}")
    public Mono<ResponseEntity<LoungePlaybackStateResponse>> promoteTrackToNowPlaying(
            @PathVariable Long loungeId,
            @PathVariable Long queueEntryId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return playbackService
                .promoteTrackToNowPlaying(loungeId, queueEntryId, authenticatedUserId)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{loungeId}/chat")
    public Mono<ResponseEntity<ChatMessageResponse>> sendChatMessage(
            @PathVariable Long loungeId,
            @Valid @RequestBody SendChatMessageRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return chatService
                .sendMessage(loungeId, request, authenticatedUserId)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @GetMapping("/{loungeId}/chat")
    public Flux<ChatMessageResponse> getChatHistory(
            @PathVariable Long loungeId,
            @RequestParam(defaultValue = "50") int limit,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return chatService.getChatHistory(loungeId, limit, authenticatedUserId);
    }

    @PostMapping("/{loungeId}/soundclash/open")
    public Mono<ResponseEntity<SoundclashBattleResponse>> openSoundclashBattle(
            @PathVariable Long loungeId,
            @Valid @RequestBody OpenSoundclashBattleRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return soundclashService
                .openBattle(loungeId, request, authenticatedUserId)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @GetMapping("/{loungeId}/soundclash/active")
    public Mono<ResponseEntity<SoundclashBattleResponse>> getActiveSoundclashBattle(
            @PathVariable Long loungeId
    ) {
        return soundclashService
                .getActiveBattle(loungeId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.noContent().build());
    }

    @PostMapping("/{loungeId}/soundclash/vote")
    public Mono<ResponseEntity<SoundclashBattleResponse>> voteSoundclash(
            @PathVariable Long loungeId,
            @Valid @RequestBody VoteSoundclashRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return soundclashService
                .voteCandidate(loungeId, request.getCandidateId(), authenticatedUserId)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{loungeId}/soundclash/resolve")
    public Mono<ResponseEntity<SoundclashBattleResponse>> resolveSoundclashBattle(
            @PathVariable Long loungeId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return soundclashService
                .resolveBattle(loungeId, authenticatedUserId)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{loungeId}/participants/{userId}/mute")
    public Mono<ResponseEntity<LoungeParticipant>> muteParticipant(
            @PathVariable Long loungeId,
            @PathVariable Long userId,
            @RequestBody(required = false) MuteParticipantRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());
        boolean muted = request == null || request.getMuted() == null || request.getMuted();

        return loungeModerationService
                .muteParticipant(loungeId, userId, muted, authenticatedUserId)
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{loungeId}/participants/{userId}")
    public Mono<ResponseEntity<LoungeParticipant>> removeParticipant(
            @PathVariable Long loungeId,
            @PathVariable Long userId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return loungeModerationService
                .removeParticipant(loungeId, userId, authenticatedUserId)
                .map(ResponseEntity::ok);
    }

    @PutMapping("/{loungeId}/lock")
    public Mono<ResponseEntity<DjLounge>> setLoungeLocked(
            @PathVariable Long loungeId,
            @RequestBody(required = false) LockLoungeRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());
        boolean locked = request == null || request.getLocked() == null || request.getLocked();

        return loungeModerationService
                .setLoungeLocked(loungeId, locked, authenticatedUserId)
                .map(ResponseEntity::ok);
    }

    @PutMapping("/{loungeId}/participants/{userId}/role")
    public Mono<ResponseEntity<LoungeParticipant>> updateParticipantRole(
            @PathVariable Long loungeId,
            @PathVariable Long userId,
            @Valid @RequestBody UpdateRoleRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return loungeModerationService
                .updateParticipantRole(loungeId, userId, request.getRole(), authenticatedUserId)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{loungeId}/transfer-host")
    public Mono<ResponseEntity<DjLounge>> transferHost(
            @PathVariable Long loungeId,
            @Valid @RequestBody TransferHostRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long authenticatedUserId = Long.valueOf(jwt.getSubject());

        return loungeModerationService
                .transferHost(loungeId, request.getNewHostUserId(), authenticatedUserId)
                .map(ResponseEntity::ok);
    }
}
