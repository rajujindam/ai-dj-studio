package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.lounge.CreateLoungeRequest;
import com.aidjstudio.ai_dj_studio.dto.lounge.CreateLoungeResponse;
import com.aidjstudio.ai_dj_studio.entity.DjLounge;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LoungeLifecycleService {

    private static final String INVITE_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final DjLoungeRepository djLoungeRepository;
    private final TrackRepository trackRepository;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public Mono<CreateLoungeResponse> createLounge(CreateLoungeRequest request, Long hostUserId) {
        return trackRepository.findById(request.getSeedTrackId())
                .switchIfEmpty(Mono.error(
                        new IllegalArgumentException("Seed track not found with id: " + request.getSeedTrackId())
                ))
                .flatMap(track -> generateUniqueInviteCode()
                        .flatMap(inviteCode -> {
                            Json vibeJson = null;
                            if (request.getVibeProfile() != null) {
                                try {
                                    vibeJson = Json.of(objectMapper.writeValueAsString(request.getVibeProfile()));
                                } catch (Exception e) {
                                    vibeJson = Json.of("\"" + request.getVibeProfile() + "\"");
                                }
                            }

                            DjLounge lounge = DjLounge.builder()
                                    .hostUserId(hostUserId)
                                    .vibeProfile(vibeJson)
                                    .currentTrackId(track.getId())
                                    .status(LoungeStatus.SCHEDULED)
                                    .bpmTolerancePct(request.getBpmTolerancePct() != null
                                            ? request.getBpmTolerancePct()
                                            : new BigDecimal("8.00"))
                                    .soundclashEnabled(request.getSoundclashEnabled() != null
                                            ? request.getSoundclashEnabled()
                                            : Boolean.TRUE)
                                    .aiVerbosity(request.getAiVerbosity() != null
                                            ? request.getAiVerbosity()
                                            : "normal")
                                    .inviteCode(inviteCode)
                                    .maxParticipants(request.getMaxParticipants() != null
                                            ? request.getMaxParticipants()
                                            : 50)
                                    .createdAt(LocalDateTime.now())
                                    .build();

                            return djLoungeRepository.save(lounge)
                                    .map(saved -> CreateLoungeResponse.builder()
                                            .loungeId(saved.getId())
                                            .inviteCode(saved.getInviteCode())
                                            .status(saved.getStatus())
                                            .hostUserId(saved.getHostUserId())
                                            .createdAt(saved.getCreatedAt())
                                            .build());
                        }));
    }

    public Mono<DjLounge> startLounge(Long loungeId, Long authenticatedUserId) {
        return findLoungeAndVerifyHost(loungeId, authenticatedUserId)
                .flatMap(lounge -> {
                    if (lounge.getStatus() != LoungeStatus.SCHEDULED) {
                        return Mono.error(new IllegalStateException(
                                "Only scheduled lounges can be started"
                        ));
                    }

                    lounge.setStatus(LoungeStatus.LIVE);
                    return djLoungeRepository.save(lounge);
                });
    }

    public Mono<DjLounge> endLounge(Long loungeId, Long authenticatedUserId) {
        return findLoungeAndVerifyHost(loungeId, authenticatedUserId)
                .flatMap(lounge -> {
                    if (lounge.getStatus() == LoungeStatus.ENDED) {
                        return Mono.error(new IllegalStateException(
                                "Lounge is already ended"
                        ));
                    }

                    lounge.setStatus(LoungeStatus.ENDED);
                    return djLoungeRepository.save(lounge);
                });
    }

    private Mono<String> generateUniqueInviteCode() {
        return Mono.defer(() -> {
            String candidate = generateCandidateInviteCode();
            return djLoungeRepository.existsByInviteCode(candidate)
                    .flatMap(exists -> exists ? generateUniqueInviteCode() : Mono.just(candidate));
        });
    }

    private String generateCandidateInviteCode() {
        StringBuilder sb = new StringBuilder("SP-");
        for (int i = 0; i < 4; i++) {
            sb.append(INVITE_CHARS.charAt(secureRandom.nextInt(INVITE_CHARS.length())));
        }
        return sb.toString();
    }

    private Mono<DjLounge> findLoungeAndVerifyHost(
            Long loungeId,
            Long authenticatedUserId
    ) {
        return djLoungeRepository.findById(loungeId)
                .switchIfEmpty(Mono.error(
                        new IllegalArgumentException(
                                "Lounge not found: " + loungeId
                        )
                ))
                .flatMap(lounge -> {
                    if (!lounge.getHostUserId().equals(authenticatedUserId)) {
                        return Mono.error(new AccessDeniedException(
                                "Only the lounge host can perform this action"
                        ));
                    }

                    return Mono.just(lounge);
                });
    }
}
