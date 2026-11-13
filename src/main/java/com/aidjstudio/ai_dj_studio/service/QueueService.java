package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.queue.AddQueueEntryRequest;
import com.aidjstudio.ai_dj_studio.dto.queue.QueueEntryResponse;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsQueueUpdatePayload;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.entity.QueueEntry;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.repository.QueueEntryRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.websocket.LoungeSessionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class QueueService {

    private final QueueEntryRepository queueEntryRepository;
    private final DjLoungeRepository djLoungeRepository;
    private final TrackRepository trackRepository;
    private final LoungeParticipantRepository loungeParticipantRepository;
    private final LoungeSessionManager loungeSessionManager;
    private final LoungeQueueMutationSerializer mutationSerializer;

    public Flux<QueueEntryResponse> getQueue(Long loungeId) {
        return findLounge(loungeId)
                .flatMapMany(lounge -> queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId))
                .map(this::toResponseDto);
    }

    public Mono<QueueEntryResponse> addTrackToQueue(
            Long loungeId,
            AddQueueEntryRequest request,
            Long authenticatedUserId
    ) {
        return mutationSerializer.executeSerialized(loungeId, () ->
                doAddTrackToQueue(loungeId, request, authenticatedUserId)
                        .flatMap(response -> broadcastQueueUpdate(loungeId, "manual").thenReturn(response))
        );
    }

    private Mono<QueueEntryResponse> doAddTrackToQueue(
            Long loungeId,
            AddQueueEntryRequest request,
            Long authenticatedUserId
    ) {
        if (loungeSessionManager.isQueueFrozen(loungeId)) {
            return Mono.error(new IllegalStateException("Queue mutations are frozen while host is disconnected"));
        }

        return findLounge(loungeId)
                .flatMap(lounge -> {
                    if (lounge.getStatus() == LoungeStatus.ENDED) {
                        return Mono.error(new IllegalStateException(
                                "Cannot modify queue of an ended lounge"
                        ));
                    }

                    return verifyParticipantPermission(lounge, authenticatedUserId)
                            .then(Mono.defer(() -> verifyTrackExists(request.getTrackId())))
                            .then(Mono.defer(() -> verifyNotDuplicate(loungeId, request.getTrackId())))
                            .then(Mono.defer(() -> calculateNextPosition(loungeId)))
                            .flatMap(nextPosition -> {
                                QueueEntry entry = new QueueEntry();
                                entry.setLoungeId(loungeId);
                                entry.setTrackId(request.getTrackId());
                                entry.setPosition(nextPosition);
                                entry.setVoteCount(0);
                                entry.setAddedByUserId(authenticatedUserId);

                                return queueEntryRepository.save(entry);
                            })
                            .map(this::toResponseDto);
                });
    }

    public Mono<Void> removeQueueEntry(
            Long loungeId,
            Long queueEntryId,
            Long authenticatedUserId
    ) {
        return mutationSerializer.executeSerialized(loungeId, () ->
                doRemoveQueueEntry(loungeId, queueEntryId, authenticatedUserId)
                        .then(Mono.defer(() -> broadcastQueueUpdate(loungeId, "manual")))
                        .then()
        );
    }

    private Mono<Void> doRemoveQueueEntry(
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
                        return Mono.error(new IllegalStateException(
                                "Cannot modify queue of an ended lounge"
                        ));
                    }

                    return queueEntryRepository.findByIdAndLoungeId(queueEntryId, loungeId)
                            .switchIfEmpty(Mono.error(new IllegalArgumentException(
                                    "Queue entry not found with id: " + queueEntryId
                            )))
                            .flatMap(entry -> {
                                boolean isHost = lounge.getHostUserId().equals(authenticatedUserId);
                                boolean isOriginalSubmitter = entry.getAddedByUserId().equals(authenticatedUserId);

                                if (!isHost && !isOriginalSubmitter) {
                                    return Mono.error(new AccessDeniedException(
                                            "Only the lounge host or the user who added this track can remove it"
                                    ));
                                }

                                return queueEntryRepository.delete(entry);
                            });
                });
    }

    public Flux<QueueEntryResponse> reorderQueueEntry(
            Long loungeId,
            Long queueEntryId,
            Integer targetPosition,
            Long authenticatedUserId
    ) {
        return mutationSerializer.executeSerializedFlux(loungeId, () ->
                doReorderQueueEntry(loungeId, queueEntryId, targetPosition, authenticatedUserId)
                        .collectList()
                        .flatMap(savedList -> broadcastQueueUpdate(loungeId, "manual").thenReturn(savedList))
                        .flatMapMany(Flux::fromIterable)
        );
    }

    private Flux<QueueEntryResponse> doReorderQueueEntry(
            Long loungeId,
            Long queueEntryId,
            Integer targetPosition,
            Long authenticatedUserId
    ) {
        if (loungeSessionManager.isQueueFrozen(loungeId)) {
            return Flux.error(new IllegalStateException("Queue mutations are frozen while host is disconnected"));
        }

        return findLounge(loungeId)
                .flatMapMany(lounge -> {
                    if (lounge.getStatus() == LoungeStatus.ENDED) {
                        return Flux.error(new IllegalStateException(
                                "Cannot modify queue of an ended lounge"
                        ));
                    }

                    if (!lounge.getHostUserId().equals(authenticatedUserId)) {
                        return Flux.error(new AccessDeniedException(
                                "Only the lounge host can reorder the queue"
                        ));
                    }

                    return queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId)
                            .collectList()
                            .flatMapMany(entries -> {
                                int currentIndex = -1;
                                for (int i = 0; i < entries.size(); i++) {
                                    if (entries.get(i).getId().equals(queueEntryId)) {
                                        currentIndex = i;
                                        break;
                                    }
                                }

                                if (currentIndex == -1) {
                                    return Flux.error(new IllegalArgumentException(
                                            "Queue entry not found with id: " + queueEntryId
                                    ));
                                }

                                QueueEntry movingEntry = entries.remove(currentIndex);
                                int clampedTarget = Math.max(1, Math.min(targetPosition, entries.size() + 1));
                                int newIndex = clampedTarget - 1;
                                entries.add(newIndex, movingEntry);

                                for (int i = 0; i < entries.size(); i++) {
                                    entries.get(i).setPosition(i + 1);
                                }

                                return queueEntryRepository.saveAll(entries)
                                        .collectList()
                                        .flatMapMany(savedList -> {
                                            savedList.sort(Comparator.comparing(QueueEntry::getPosition));
                                            return Flux.fromIterable(savedList);
                                        })
                                        .map(this::toResponseDto);
                            });
                });
    }

    public Flux<QueueEntryResponse> moveQueueEntryToTop(
            Long loungeId,
            Long queueEntryId,
            Long authenticatedUserId
    ) {
        return reorderQueueEntry(loungeId, queueEntryId, 1, authenticatedUserId);
    }

    public Mono<List<QueueEntryResponse>> broadcastQueueUpdate(Long loungeId, String reason) {
        return queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId)
                .map(this::toResponseDto)
                .collectList()
                .doOnNext(entries -> {
                    WsQueueUpdatePayload payload = WsQueueUpdatePayload.builder()
                            .loungeId(loungeId)
                            .reason(reason)
                            .queue(entries)
                            .nextUp(entries)
                            .build();

                    WsEventEnvelope<WsQueueUpdatePayload> envelope = WsEventEnvelope.<WsQueueUpdatePayload>builder()
                            .event("lounge.queue.update")
                            .loungeId(loungeId)
                            .payload(payload)
                            .ts(Instant.now())
                            .build();

                    loungeSessionManager.broadcast(loungeId, envelope);
                });
    }

    private Mono<DjLounge> findLounge(Long loungeId) {
        return djLoungeRepository.findById(loungeId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Lounge not found: " + loungeId
                )));
    }

    private Mono<Void> verifyParticipantPermission(DjLounge lounge, Long authenticatedUserId) {
        if (lounge.getHostUserId().equals(authenticatedUserId)) {
            return Mono.empty();
        }

        return loungeParticipantRepository
                .findByLoungeIdAndUserIdAndLeftAtIsNull(lounge.getId(), authenticatedUserId)
                .switchIfEmpty(Mono.error(new AccessDeniedException(
                        "User is not an active participant of this lounge"
                )))
                .then();
    }

    private Mono<Void> verifyTrackExists(Long trackId) {
        return trackRepository.findById(trackId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Track not found with id: " + trackId
                )))
                .then();
    }

    private Mono<Void> verifyNotDuplicate(Long loungeId, Long trackId) {
        return queueEntryRepository.existsByLoungeIdAndTrackId(loungeId, trackId)
                .flatMap(exists -> Boolean.TRUE.equals(exists)
                        ? Mono.error(new IllegalStateException("Track is already in queue"))
                        : Mono.empty());
    }

    private Mono<Integer> calculateNextPosition(Long loungeId) {
        return queueEntryRepository.findByLoungeIdOrderByPositionAsc(loungeId)
                .map(QueueEntry::getPosition)
                .reduce(0, Math::max)
                .map(maxPosition -> maxPosition + 1);
    }

    public QueueEntryResponse toResponseDto(QueueEntry entry) {
        return QueueEntryResponse.builder()
                .id(entry.getId())
                .loungeId(entry.getLoungeId())
                .trackId(entry.getTrackId())
                .position(entry.getPosition())
                .voteCount(entry.getVoteCount())
                .addedByUserId(entry.getAddedByUserId())
                .build();
    }
}
