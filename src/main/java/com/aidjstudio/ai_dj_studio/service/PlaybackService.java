package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.queue.LoungePlaybackStateResponse;
import com.aidjstudio.ai_dj_studio.dto.queue.QueueEntryResponse;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.entity.QueueEntry;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.QueueEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PlaybackService {

    private final DjLoungeRepository djLoungeRepository;
    private final QueueEntryRepository queueEntryRepository;
    private final QueueService queueService;

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

                                QueueEntry nextEntry = entries.remove(0);
                                lounge.setCurrentTrackId(nextEntry.getTrackId());

                                for (int i = 0; i < entries.size(); i++) {
                                    entries.get(i).setPosition(i + 1);
                                }

                                return queueEntryRepository.saveAll(entries)
                                        .then(queueEntryRepository.delete(nextEntry))
                                        .then(djLoungeRepository.save(lounge))
                                        .flatMap(savedLounge -> getPlaybackState(loungeId));
                            });
                });
    }

    public Mono<LoungePlaybackStateResponse> promoteTrackToNowPlaying(
            Long loungeId,
            Long queueEntryId,
            Long authenticatedUserId
    ) {
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
                                lounge.setCurrentTrackId(entry.getTrackId());

                                return queueEntryRepository.delete(entry)
                                        .then(djLoungeRepository.save(lounge))
                                        .then(normalizeRemainingPositions(loungeId))
                                        .then(getPlaybackState(loungeId));
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
