package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.queue.AddQueueEntryRequest;
import com.aidjstudio.ai_dj_studio.dto.queue.QueueEntryResponse;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.entity.QueueEntry;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import com.aidjstudio.ai_dj_studio.repository.QueueEntryRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class QueueService {

    private final QueueEntryRepository queueEntryRepository;
    private final DjLoungeRepository djLoungeRepository;
    private final TrackRepository trackRepository;
    private final LoungeParticipantRepository loungeParticipantRepository;

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

    private QueueEntryResponse toResponseDto(QueueEntry entry) {
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
