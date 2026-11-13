package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipant;
import com.aidjstudio.ai_dj_studio.entity.LoungeParticipantRole;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.LoungeParticipantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LoungeMembershipService {

    private final DjLoungeRepository djLoungeRepository;
    private final LoungeParticipantRepository loungeParticipantRepository;

    public Mono<LoungeParticipant> joinLounge(
            Long loungeId,
            Long authenticatedUserId
    ) {
        return findLounge(loungeId)
                .flatMap(lounge -> {
                    if (lounge.getStatus() == LoungeStatus.ENDED) {
                        return Mono.error(new IllegalStateException(
                                "Cannot join an ended lounge"
                        ));
                    }

                    if (lounge.getHostUserId().equals(authenticatedUserId)) {
                        return Mono.error(new IllegalStateException(
                                "The lounge host does not need participant membership"
                        ));
                    }

                    return loungeParticipantRepository
                            .findByLoungeIdAndUserIdAndLeftAtIsNull(
                                    loungeId,
                                    authenticatedUserId
                            )
                            .flatMap(existingMembership ->
                                    Mono.<LoungeParticipant>error(
                                            new IllegalStateException(
                                                    "User is already an active participant"
                                            )
                                    )
                            )
                            .switchIfEmpty(Mono.defer(() -> {
                                LoungeParticipant participant =
                                        new LoungeParticipant();

                                participant.setLoungeId(loungeId);
                                participant.setUserId(authenticatedUserId);
                                participant.setRole(
                                        LoungeParticipantRole.LISTENER
                                );
                                participant.setJoinedAt(
                                        LocalDateTime.now()
                                );
                                participant.setLeftAt(null);

                                return loungeParticipantRepository.save(
                                        participant
                                );
                            }));
                });
    }

    public Mono<LoungeParticipant> joinLoungeIdempotent(
            Long loungeId,
            Long authenticatedUserId
    ) {
        return findLounge(loungeId)
                .flatMap(lounge -> {
                    if (lounge.getStatus() == LoungeStatus.ENDED) {
                        return Mono.error(new IllegalStateException(
                                "Cannot join an ended lounge"
                        ));
                    }

                    if (lounge.getHostUserId().equals(authenticatedUserId)) {
                        return Mono.error(new IllegalStateException(
                                "The lounge host does not need participant membership"
                        ));
                    }

                    return loungeParticipantRepository
                            .findByLoungeIdAndUserIdAndLeftAtIsNull(
                                    loungeId,
                                    authenticatedUserId
                            )
                            .switchIfEmpty(Mono.defer(() -> {
                                LoungeParticipant participant =
                                        new LoungeParticipant();

                                participant.setLoungeId(loungeId);
                                participant.setUserId(authenticatedUserId);
                                participant.setRole(
                                        LoungeParticipantRole.LISTENER
                                );
                                participant.setJoinedAt(
                                        LocalDateTime.now()
                                );
                                participant.setLeftAt(null);

                                return loungeParticipantRepository.save(
                                        participant
                                );
                            }));
                });
    }

    public Mono<LoungeParticipant> leaveLounge(
            Long loungeId,
            Long authenticatedUserId
    ) {
        return findLounge(loungeId)
                .flatMap(lounge ->
                        loungeParticipantRepository
                                .findByLoungeIdAndUserIdAndLeftAtIsNull(
                                        loungeId,
                                        authenticatedUserId
                                )
                                .switchIfEmpty(Mono.error(
                                        new IllegalStateException(
                                                "User is not an active participant"
                                        )
                                			))
                                .flatMap(participant -> {
                                    participant.setLeftAt(
                                            LocalDateTime.now()
                                    );

                                    return loungeParticipantRepository.save(
                                            participant
                                    );
                                })
                );
    }

    private Mono<DjLounge> findLounge(Long loungeId) {
        return djLoungeRepository.findById(loungeId)
                .switchIfEmpty(Mono.error(
                        new IllegalArgumentException(
                                "Lounge not found: " + loungeId
                        )
                ));
    }

    public Flux<LoungeParticipant> getActiveParticipants(Long loungeId) {
        return findLounge(loungeId)
                .flatMapMany(lounge ->
                        loungeParticipantRepository
                                .findByLoungeIdAndLeftAtIsNullOrderByJoinedAtAsc(loungeId)
                );
    }
}
