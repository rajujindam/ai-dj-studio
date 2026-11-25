package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.queue.LoungePlaybackStateResponse;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.entity.QueueEntry;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.QueueEntryRepository;
import com.aidjstudio.ai_dj_studio.websocket.LoungeSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class PlaybackService {

    private final DjLoungeRepository djLoungeRepository;
    private final QueueEntryRepository queueEntryRepository;
    private final QueueService queueService;
    private final LoungeSessionManager loungeSessionManager;
    private final LoungeQueueMutationSerializer mutationSerializer;
    private final InteractionService interactionService;

    @Autowired
    public PlaybackService(
            DjLoungeRepository djLoungeRepository,
            QueueEntryRepository queueEntryRepository,
            QueueService queueService,
            LoungeSessionManager loungeSessionManager,
            LoungeQueueMutationSerializer mutationSerializer,
            @Autowired(required = false) InteractionService interactionService
    ) {
        this.djLoungeRepository = djLoungeRepository;
        this.queueEntryRepository = queueEntryRepository;
        this.queueService = queueService;
        this.loungeSessionManager = loungeSessionManager;
        this.mutationSerializer = mutationSerializer;
        this.interactionService = interactionService;
    }

    public PlaybackService(
            DjLoungeRepository djLoungeRepository,
            QueueEntryRepository queueEntryRepository,
            QueueService queueService,
            LoungeSessionManager loungeSessionManager,
            LoungeQueueMutationSerializer mutationSerializer
    ) {
        this(djLoungeRepository, queueEntryRepository, queueService, loungeSessionManager, mutationSerializer, null);
    }

    public Mono<LoungePlaybackStateResponse> getPlaybackState(Long loungeId) {
        return findLounge(loungeId)
                .flatMap(lounge -> queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId)
                        .map(queueService::toResponseDto)
                        .collectList()
                        .map(nextUp -> LoungePlaybackStateResponse.builder()
                                .loungeId(lounge.getId())
                                .status(lounge.getStatus())
                                .currentTrackId(lounge.getCurrentTrackId())
                                .nextUp(nextUp)
                                .build()));
    }

    public Mono<LoungePlaybackStateResponse> advanceToNextTrack(Long loungeId, Long authenticatedUserId) {
        return mutationSerializer.executeSerialized(loungeId, () ->
                doAdvanceToNextTrack(loungeId, authenticatedUserId)
                        .flatMap(response -> queueService.broadcastQueueUpdate(loungeId, "playback").thenReturn(response))
        );
    }

    private Mono<LoungePlaybackStateResponse> doAdvanceToNextTrack(Long loungeId, Long authenticatedUserId) {
        if (loungeSessionManager.isQueueFrozen(loungeId)) {
            return Mono.error(new IllegalStateException("Queue mutations are frozen while host is disconnected"));
        }

        return findLounge(loungeId)
                .flatMap(lounge -> {
                    if (lounge.getStatus() == LoungeStatus.ENDED) {
                        return Mono.error(new IllegalStateException("Cannot modify playback of an ended lounge"));
                    }

                    if (lounge.getStatus() != LoungeStatus.LIVE) {
                        return Mono.error(new IllegalStateException("Lounge must be LIVE to advance playback"));
                    }

                    if (!lounge.getHostUserId().equals(authenticatedUserId)) {
                        return Mono.error(new AccessDeniedException("Only the lounge host can control playback"));
                    }

                    return queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId)
                            .collectList()
                            .flatMap(entries -> {
                                if (entries.isEmpty()) {
                                    return Mono.error(new IllegalStateException("Queue is empty"));
                                }

                                Long previousTrackId = lounge.getCurrentTrackId();
                                QueueEntry nextEntry = entries.remove(0);
                                lounge.setCurrentTrackId(nextEntry.getTrackId());

                                for (int i = 0; i < entries.size(); i++) {
                                    entries.get(i).setPosition(i + 1);
                                }

                                return queueEntryRepository.saveAll(entries)
                                        .then(queueEntryRepository.delete(nextEntry))
                                        .then(djLoungeRepository.save(lounge))
                                        .then(Mono.defer(() -> {
                                            if (interactionService != null && previousTrackId != null) {
                                                return interactionService.recordCoListening(loungeId, previousTrackId, null, true)
                                                        .collectList()
                                                        .onErrorResume(ex -> {
                                                            log.warn("Failed to capture co-listening on track advance: {}", ex.getMessage());
                                                            return Mono.empty();
                                                        })
                                                        .then(getPlaybackState(loungeId));
                                            }
                                            return getPlaybackState(loungeId);
                                        }));
                            });
                });
    }

    public Mono<LoungePlaybackStateResponse> promoteTrackToNowPlaying(
            Long loungeId,
            Long queueEntryId,
            Long authenticatedUserId
    ) {
        return mutationSerializer.executeSerialized(loungeId, () ->
                doPromoteTrackToNowPlaying(loungeId, queueEntryId, authenticatedUserId)
                        .flatMap(response -> queueService.broadcastQueueUpdate(loungeId, "playback").thenReturn(response))
        );
    }

    private Mono<LoungePlaybackStateResponse> doPromoteTrackToNowPlaying(
            Long loungeId,
            Long queueEntryId,
            Long authenticatedUserId
    ) {
        if (loungeSessionManager.isQueueFrozen(loungeId)) {
            return Mono.error(new IllegalStateException("Queue mutations are frozen while host is disconnected"));
        }

        return findLounge(loungeId)
                .flatMap(lounge -> {
                    if (lounge.getStatus() == LoungeStatus.ENDED) {
                        return Mono.error(new IllegalStateException("Cannot modify playback of an ended lounge"));
                    }

                    if (lounge.getStatus() != LoungeStatus.LIVE) {
                        return Mono.error(new IllegalStateException("Lounge must be LIVE to promote a track"));
                    }

                    if (!lounge.getHostUserId().equals(authenticatedUserId)) {
                        return Mono.error(new AccessDeniedException("Only the lounge host can control playback"));
                    }

                    return queueEntryRepository.findByIdAndLoungeId(queueEntryId, loungeId)
                            .switchIfEmpty(Mono.error(new IllegalArgumentException(
                                    "Queue entry not found with id: " + queueEntryId
                             )))
                            .flatMap(entry -> {
                                Long previousTrackId = lounge.getCurrentTrackId();
                                lounge.setCurrentTrackId(entry.getTrackId());

                                return queueEntryRepository.delete(entry)
                                        .then(djLoungeRepository.save(lounge))
                                        .then(normalizeRemainingPositions(loungeId))
                                        .then(Mono.defer(() -> {
                                            if (interactionService != null && previousTrackId != null) {
                                                return interactionService.recordCoListening(loungeId, previousTrackId, null, true)
                                                        .collectList()
                                                        .onErrorResume(ex -> {
                                                            log.warn("Failed to capture co-listening on track promotion: {}", ex.getMessage());
                                                            return Mono.empty();
                                                        })
                                                        .then(getPlaybackState(loungeId));
                                            }
                                            return getPlaybackState(loungeId);
                                        }));
                            });
                });
    }

    private Mono<Void> normalizeRemainingPositions(Long loungeId) {
        return queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId)
                .collectList()
                .flatMap(entries -> {
                    for (int i = 0; i < entries.size(); i++) {
                        entries.get(i).setPosition(i + 1);
                    }
                    return queueEntryRepository.saveAll(entries).then();
                });
    }

    private Mono<DjLounge> findLounge(Long loungeId) {
        return djLoungeRepository.findById(loungeId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Lounge not found: " + loungeId
                )));
    }
}
