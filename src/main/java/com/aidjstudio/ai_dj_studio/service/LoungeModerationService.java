package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipantRole;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.websocket.LoungeSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoungeModerationService {

    private final DjLoungeRepository djLoungeRepository;
    private final LoungeParticipantRepository loungeParticipantRepository;
    private final LoungeSessionManager loungeSessionManager;

    /**
     * Mutes or unmutes a participant in the lounge.
     * Host can mute any participant (Listener or Co-DJ).
     * Co-DJ can only mute Listeners.
     * Host cannot be muted.
     */
    public Mono<LoungeParticipant> muteParticipant(Long loungeId, Long targetUserId, boolean muted, Long callerUserId) {
        if (loungeId == null) {
            return Mono.error(new IllegalArgumentException("Lounge ID cannot be null"));
        }
        if (targetUserId == null) {
            return Mono.error(new IllegalArgumentException("Target user ID cannot be null"));
        }
        if (callerUserId == null) {
            return Mono.error(new AccessDeniedException("User must be authenticated"));
        }

        return findActiveLounge(loungeId)
                .flatMap(lounge -> {
                    if (targetUserId.equals(lounge.getHostUserId())) {
                        return Mono.error(new IllegalStateException("Cannot mute the lounge host"));
                    }

                    return verifyModeratorPermission(lounge, callerUserId)
                            .flatMap(callerRole -> findActiveParticipant(loungeId, targetUserId)
                                    .flatMap(targetParticipant -> {
                                        // Co-DJ cannot mute another Co-DJ
                                        if ("CO_DJ".equals(callerRole) && targetParticipant.getRole() == LoungeParticipantRole.CO_DJ) {
                                            return Mono.error(new AccessDeniedException("Co-DJ cannot mute another Co-DJ"));
                                        }

                                        targetParticipant.setIsMuted(muted);
                                        return loungeParticipantRepository.save(targetParticipant)
                                                .doOnNext(saved -> broadcastModerationEvent(
                                                        loungeId,
                                                        "lounge.participant.muted",
                                                        Map.of("userId", targetUserId, "muted", muted, "moderatorId", callerUserId)
                                                ));
                                    })
                            );
                });
    }

    /**
     * Removes/kicks a participant from the lounge.
     * Host can remove any participant.
     * Co-DJ can only remove Listeners.
     * Host cannot be removed.
     */
    public Mono<LoungeParticipant> removeParticipant(Long loungeId, Long targetUserId, Long callerUserId) {
        if (loungeId == null) {
            return Mono.error(new IllegalArgumentException("Lounge ID cannot be null"));
        }
        if (targetUserId == null) {
            return Mono.error(new IllegalArgumentException("Target user ID cannot be null"));
        }
        if (callerUserId == null) {
            return Mono.error(new AccessDeniedException("User must be authenticated"));
        }

        return findActiveLounge(loungeId)
                .flatMap(lounge -> {
                    if (targetUserId.equals(lounge.getHostUserId())) {
                        return Mono.error(new IllegalStateException("Cannot remove the lounge host"));
                    }

                    return verifyModeratorPermission(lounge, callerUserId)
                            .flatMap(callerRole -> findActiveParticipant(loungeId, targetUserId)
                                    .flatMap(targetParticipant -> {
                                        // Co-DJ cannot remove another Co-DJ
                                        if ("CO_DJ".equals(callerRole) && targetParticipant.getRole() == LoungeParticipantRole.CO_DJ) {
                                            return Mono.error(new AccessDeniedException("Co-DJ cannot remove another Co-DJ"));
                                        }

                                        targetParticipant.setLeftAt(LocalDateTime.now());
                                        return loungeParticipantRepository.save(targetParticipant)
                                                .doOnNext(saved -> {
                                                    loungeSessionManager.disconnectUser(loungeId, targetUserId, "Removed by moderator");
                                                    broadcastModerationEvent(
                                                            loungeId,
                                                            "lounge.participant.removed",
                                                            Map.of("userId", targetUserId, "removedByUserId", callerUserId)
                                                    );
                                                });
                                    })
                            );
                });
    }

    /**
     * Locks or unlocks the lounge.
     * When locked, new listeners cannot join.
     * Only the Host can lock or unlock the lounge.
     */
    public Mono<DjLounge> setLoungeLocked(Long loungeId, boolean locked, Long callerUserId) {
        if (loungeId == null) {
            return Mono.error(new IllegalArgumentException("Lounge ID cannot be null"));
        }
        if (callerUserId == null) {
            return Mono.error(new AccessDeniedException("User must be authenticated"));
        }

        return findActiveLounge(loungeId)
                .flatMap(lounge -> {
                    if (!lounge.getHostUserId().equals(callerUserId)) {
                        return Mono.error(new AccessDeniedException("Only the lounge host can lock or unlock the lounge"));
                    }

                    lounge.setIsLocked(locked);
                    return djLoungeRepository.save(lounge)
                            .doOnNext(saved -> broadcastModerationEvent(
                                    loungeId,
                                    "lounge.lock.updated",
                                    Map.of("loungeId", loungeId, "locked", locked)
                            ));
                });
    }

    /**
     * Promotes or demotes an active participant's role (e.g. LISTENER <-> CO_DJ).
     * Only the Host can change participant roles.
     */
    public Mono<LoungeParticipant> updateParticipantRole(Long loungeId, Long targetUserId, LoungeParticipantRole newRole, Long callerUserId) {
        if (loungeId == null) {
            return Mono.error(new IllegalArgumentException("Lounge ID cannot be null"));
        }
        if (targetUserId == null) {
            return Mono.error(new IllegalArgumentException("Target user ID cannot be null"));
        }
        if (newRole == null) {
            return Mono.error(new IllegalArgumentException("Role cannot be null"));
        }
        if (callerUserId == null) {
            return Mono.error(new AccessDeniedException("User must be authenticated"));
        }

        return findActiveLounge(loungeId)
                .flatMap(lounge -> {
                    if (!lounge.getHostUserId().equals(callerUserId)) {
                        return Mono.error(new AccessDeniedException("Only the lounge host can update participant roles"));
                    }
                    if (targetUserId.equals(lounge.getHostUserId())) {
                        return Mono.error(new IllegalStateException("Cannot change the role of the lounge host"));
                    }

                    return findActiveParticipant(loungeId, targetUserId)
                            .flatMap(targetParticipant -> {
                                targetParticipant.setRole(newRole);
                                return loungeParticipantRepository.save(targetParticipant)
                                        .doOnNext(saved -> {
                                            loungeSessionManager.updateUserRole(loungeId, targetUserId, newRole.name());
                                            broadcastModerationEvent(
                                                    loungeId,
                                                    "lounge.role.updated",
                                                    Map.of("userId", targetUserId, "role", newRole.name())
                                            );
                                        });
                            });
                });
    }

    /**
     * Transfers host ownership to an active participant.
     * The target user becomes the host, and their participant membership row is closed.
     * The former host becomes a Co-DJ with a new participant membership.
     * Only the current Host can transfer ownership.
     */
    public Mono<DjLounge> transferHost(Long loungeId, Long newHostUserId, Long callerUserId) {
        if (loungeId == null) {
            return Mono.error(new IllegalArgumentException("Lounge ID cannot be null"));
        }
        if (newHostUserId == null) {
            return Mono.error(new IllegalArgumentException("New host user ID cannot be null"));
        }
        if (callerUserId == null) {
            return Mono.error(new AccessDeniedException("User must be authenticated"));
        }

        return findActiveLounge(loungeId)
                .flatMap(lounge -> {
                    if (!lounge.getHostUserId().equals(callerUserId)) {
                        return Mono.error(new AccessDeniedException("Only the current lounge host can transfer ownership"));
                    }
                    if (newHostUserId.equals(callerUserId)) {
                        return Mono.error(new IllegalStateException("Cannot transfer host ownership to yourself"));
                    }

                    return findActiveParticipant(loungeId, newHostUserId)
                            .flatMap(targetParticipant -> {
                                LocalDateTime now = LocalDateTime.now();

                                // Close target's listener/co-dj membership (since they are now host)
                                targetParticipant.setLeftAt(now);

                                // Create a Co-DJ membership for the former host
                                LoungeParticipant formerHostParticipant = new LoungeParticipant();
                                formerHostParticipant.setLoungeId(loungeId);
                                formerHostParticipant.setUserId(callerUserId);
                                formerHostParticipant.setRole(LoungeParticipantRole.CO_DJ);
                                formerHostParticipant.setIsMuted(false);
                                formerHostParticipant.setJoinedAt(now);
                                formerHostParticipant.setLeftAt(null);

                                // Update lounge host
                                lounge.setHostUserId(newHostUserId);

                                return loungeParticipantRepository.save(targetParticipant)
                                        .then(loungeParticipantRepository.save(formerHostParticipant))
                                        .then(djLoungeRepository.save(lounge))
                                        .doOnNext(savedLounge -> {
                                            loungeSessionManager.recordHostUser(loungeId, newHostUserId);
                                            loungeSessionManager.updateUserRole(loungeId, newHostUserId, "HOST");
                                            loungeSessionManager.updateUserRole(loungeId, callerUserId, "CO_DJ");
                                            broadcastModerationEvent(
                                                    loungeId,
                                                    "lounge.host.transferred",
                                                    Map.of("previousHostUserId", callerUserId, "newHostUserId", newHostUserId)
                                            );
                                        });
                            });
                });
    }

    private Mono<DjLounge> findActiveLounge(Long loungeId) {
        return djLoungeRepository.findById(loungeId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Lounge not found with id: " + loungeId)))
                .flatMap(lounge -> {
                    if (lounge.getStatus() == LoungeStatus.ENDED) {
                        return Mono.error(new IllegalStateException("Cannot perform moderation in an ended lounge"));
                    }
                    return Mono.just(lounge);
                });
    }

    private Mono<String> verifyModeratorPermission(DjLounge lounge, Long callerUserId) {
        if (lounge.getHostUserId().equals(callerUserId)) {
            return Mono.just("HOST");
        }

        return loungeParticipantRepository
                .findByLoungeIdAndUserIdAndLeftAtIsNull(lounge.getId(), callerUserId)
                .switchIfEmpty(Mono.error(new AccessDeniedException("User is not an active participant of this lounge")))
                .flatMap(participant -> {
                    if (participant.getRole() == LoungeParticipantRole.CO_DJ) {
                        return Mono.just("CO_DJ");
                    }
                    return Mono.error(new AccessDeniedException("Only the Host or Co-DJ can perform moderation actions"));
                });
    }

    private Mono<LoungeParticipant> findActiveParticipant(Long loungeId, Long userId) {
        return loungeParticipantRepository
                .findByLoungeIdAndUserIdAndLeftAtIsNull(loungeId, userId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("User " + userId + " is not an active participant in lounge " + loungeId)));
    }

    private void broadcastModerationEvent(Long loungeId, String event, Object payload) {
        WsEventEnvelope<Object> envelope = WsEventEnvelope.builder()
                .event(event)
                .loungeId(loungeId)
                .payload(payload)
                .ts(Instant.now())
                .build();

        loungeSessionManager.broadcast(loungeId, envelope);
    }
}
