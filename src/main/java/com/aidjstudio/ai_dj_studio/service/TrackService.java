package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.track.AcousticFeaturesDto;
import com.aidjstudio.ai_dj_studio.dto.track.TimelineEventDto;
import com.aidjstudio.ai_dj_studio.dto.track.TrackAcousticProfileResponse;
import com.aidjstudio.ai_dj_studio.dto.track.TrackResponse;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.entity.TrackTimelineEvent;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackTimelineEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class TrackService {

    private static final Pattern CAMELOT_PATTERN = Pattern.compile("^(1[0-2]|[1-9])[ABab]$");

    private final TrackRepository trackRepository;
    private final TrackTimelineEventRepository timelineEventRepository;
    private final ObjectMapper objectMapper;

    public Flux<TrackResponse> getAllTracks() {
        return trackRepository.findAll()
                .map(TrackResponse::fromEntity);
    }

    public Mono<TrackResponse> getTrackById(Long id) {
        return trackRepository.findById(id)
                .switchIfEmpty(Mono.error(
                        new IllegalArgumentException("Track not found with id: " + id)
                ))
                .map(TrackResponse::fromEntity);
    }

    public Mono<TrackAcousticProfileResponse> getAcousticProfileByTrackId(Long id) {
        return trackRepository.findById(id)
                .switchIfEmpty(Mono.error(
                        new IllegalArgumentException("Track not found with id: " + id)
                ))
                .flatMap(track -> {
                    String normalizedKey = validateAndNormalizeCamelotKey(track);
                    validateAcousticMetrics(track);

                    return timelineEventRepository.findByTrackIdOrderByTsSecondsAsc(id)
                            .map(this::mapTimelineEvent)
                            .collectList()
                            .map(timelineEvents -> TrackAcousticProfileResponse.builder()
                                    .trackId(track.getId())
                                    .title(track.getTitle())
                                    .artist(track.getArtist())
                                    .acoustic(AcousticFeaturesDto.builder()
                                            .bpm(track.getBpm())
                                            .camelotKey(normalizedKey)
                                            .energy(track.getEnergy())
                                            .valence(track.getValence())
                                            .build())
                                    .timeline(timelineEvents)
                                    .build());
                });
    }

    private String validateAndNormalizeCamelotKey(Track track) {
        if (track.getCamelotKey() == null) {
            throw new IllegalArgumentException("Track acoustic profile not found or incomplete for id: " + track.getId());
        }
        String trimmed = track.getCamelotKey().trim();
        if (!CAMELOT_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Track acoustic profile not found or incomplete for id: " + track.getId());
        }
        return trimmed.toUpperCase();
    }

    private void validateAcousticMetrics(Track track) {
        if (track.getBpm() == null || track.getBpm().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Track acoustic profile not found or incomplete for id: " + track.getId());
        }
        if (track.getEnergy() == null || track.getEnergy().compareTo(BigDecimal.ZERO) < 0 || track.getEnergy().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Track acoustic profile not found or incomplete for id: " + track.getId());
        }
        if (track.getValence() == null || track.getValence().compareTo(BigDecimal.ZERO) < 0 || track.getValence().compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Track acoustic profile not found or incomplete for id: " + track.getId());
        }
    }

    private TimelineEventDto mapTimelineEvent(TrackTimelineEvent event) {
        String text = null;
        if (event.getPayload() != null) {
            String jsonStr = event.getPayload().asString();
            try {
                JsonNode root = objectMapper.readTree(jsonStr);
                if (root.has("text")) {
                    text = root.get("text").asText();
                } else if (root.isTextual()) {
                    text = root.asText();
                } else {
                    text = jsonStr;
                }
            } catch (Exception e) {
                text = jsonStr;
            }
        }
        return TimelineEventDto.builder()
                .id(event.getId())
                .ts(event.getTsSeconds())
                .type(event.getEventType())
                .text(text)
                .build();
    }
}
