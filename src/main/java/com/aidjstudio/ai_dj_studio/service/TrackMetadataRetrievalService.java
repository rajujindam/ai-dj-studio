package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.track.RetrievedMetadataEntry;
import com.aidjstudio.ai_dj_studio.entity.TrackTimelineEvent;
import com.aidjstudio.ai_dj_studio.repository.TrackTimelineEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TrackMetadataRetrievalService {

    private static final int DEFAULT_LIMIT = 10;
    private static final Pattern TOKEN_SPLIT_PATTERN = Pattern.compile("[\\s,\\.\\?\\!\\-\\_\":;\\[\\]\\(\\)\\/]+");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "in", "on", "at", "to", "for", "of", "with",
            "is", "are", "was", "were", "what", "who", "where", "when", "why", "how",
            "this", "that", "it", "track", "song"
    );

    private final TrackTimelineEventRepository timelineEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Retrieve relevant authored timeline metadata entries for a single track.
     * Enforces strict track isolation: only events with the given trackId are searched.
     */
    public Flux<RetrievedMetadataEntry> retrieve(Long trackId, String query) {
        return retrieve(trackId, query, null, DEFAULT_LIMIT);
    }

    /**
     * Retrieve relevant authored timeline metadata entries for a track with optional event-type filtering and limit.
     */
    public Flux<RetrievedMetadataEntry> retrieve(Long trackId, String query, String eventType, Integer limit) {
        if (trackId == null) {
            return Flux.empty();
        }

        int maxResults = (limit != null && limit > 0) ? limit : DEFAULT_LIMIT;

        return timelineEventRepository.findByTrackIdOrderByTsSecondsAsc(trackId)
                .collectList()
                .flatMapMany(events -> filterAndScore(events, query, eventType, maxResults));
    }

    /**
     * Retrieve relevant authored timeline metadata entries across multiple specific tracks (e.g. current and recent tracks).
     * Enforces track isolation across the requested track ID collection.
     */
    public Flux<RetrievedMetadataEntry> retrieveForTracks(Collection<Long> trackIds, String query, String eventType, Integer limit) {
        if (trackIds == null || trackIds.isEmpty()) {
            return Flux.empty();
        }

        int maxResults = (limit != null && limit > 0) ? limit : DEFAULT_LIMIT;

        return timelineEventRepository.findByTrackIdInOrderByTsSecondsAsc(trackIds)
                .collectList()
                .flatMapMany(events -> filterAndScore(events, query, eventType, maxResults));
    }

    /**
     * Deterministically builds a stable source ID for a timeline event.
     * Format: "track:{trackId}:timeline:{eventId}"
     */
    public static String buildStableSourceId(Long trackId, Long eventId) {
        return "track:" + trackId + ":timeline:" + eventId;
    }

    private Flux<RetrievedMetadataEntry> filterAndScore(
            List<TrackTimelineEvent> events,
            String query,
            String eventTypeFilter,
            int limit
    ) {
        if (events.isEmpty()) {
            return Flux.empty();
        }

        String normalizedTypeFilter = (eventTypeFilter != null && !eventTypeFilter.isBlank())
                ? eventTypeFilter.trim().toLowerCase(Locale.ROOT)
                : null;

        boolean hasQuery = (query != null && !query.isBlank());
        String normalizedQuery = hasQuery ? query.trim().toLowerCase(Locale.ROOT) : "";
        List<String> queryTokens = hasQuery ? extractKeywords(normalizedQuery) : Collections.emptyList();

        List<RetrievedMetadataEntry> scoredEntries = new ArrayList<>();

        for (TrackTimelineEvent event : events) {
            // Apply structured event-type filter if specified
            if (normalizedTypeFilter != null) {
                if (event.getEventType() == null ||
                        !event.getEventType().toLowerCase(Locale.ROOT).equals(normalizedTypeFilter)) {
                    continue;
                }
            }

            String authoredText = extractAuthoredText(event);

            if (!hasQuery) {
                // If no query string was provided, return all matching entries ordered by timeline position
                scoredEntries.add(RetrievedMetadataEntry.builder()
                        .id(event.getId())
                        .trackId(event.getTrackId())
                        .sourceId(buildStableSourceId(event.getTrackId(), event.getId()))
                        .tsSeconds(event.getTsSeconds())
                        .eventType(event.getEventType())
                        .text(authoredText)
                        .score(1.0)
                        .build());
            } else {
                double score = computeRelevanceScore(event, authoredText, normalizedQuery, queryTokens);
                if (score > 0.0) {
                    scoredEntries.add(RetrievedMetadataEntry.builder()
                            .id(event.getId())
                            .trackId(event.getTrackId())
                            .sourceId(buildStableSourceId(event.getTrackId(), event.getId()))
                            .tsSeconds(event.getTsSeconds())
                            .eventType(event.getEventType())
                            .text(authoredText)
                            .score(Math.round(score * 100.0) / 100.0)
                            .build());
                }
            }
        }

        if (hasQuery) {
            // Sort by relevance score DESC, then timestamp ASC, then ID ASC for determinism
            scoredEntries.sort(Comparator
                    .comparing(RetrievedMetadataEntry::getScore, Comparator.reverseOrder())
                    .thenComparing(RetrievedMetadataEntry::getTsSeconds, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(RetrievedMetadataEntry::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        } else {
            // Unqueried: sort chronologically by timeline timestamp, then ID
            scoredEntries.sort(Comparator
                    .comparing(RetrievedMetadataEntry::getTsSeconds, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(RetrievedMetadataEntry::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        }

        if (scoredEntries.size() > limit) {
            scoredEntries = scoredEntries.subList(0, limit);
        }

        return Flux.fromIterable(scoredEntries);
    }

    private double computeRelevanceScore(
            TrackTimelineEvent event,
            String text,
            String normalizedQuery,
            List<String> queryTokens
    ) {
        double score = 0.0;
        String normalizedText = (text != null) ? text.toLowerCase(Locale.ROOT) : "";
        String normalizedEventType = (event.getEventType() != null) ? event.getEventType().toLowerCase(Locale.ROOT) : "";

        // Exact phrase match bonus
        if (!normalizedQuery.isBlank() && normalizedText.contains(normalizedQuery)) {
            score += 5.0;
        }

        // Token match scoring
        for (String token : queryTokens) {
            if (token.isBlank()) {
                continue;
            }

            boolean matchedInText = normalizedText.contains(token);
            boolean matchedInEventType = normalizedEventType.contains(token);

            if (matchedInText) {
                // Word-boundary match gets higher weight than pure substring
                if (containsWord(normalizedText, token)) {
                    score += 2.0;
                } else {
                    score += 1.0;
                }
            }

            if (matchedInEventType) {
                score += 1.5;
            }
        }

        return score;
    }

    private boolean containsWord(String source, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(source).find();
    }

    private List<String> extractKeywords(String query) {
        String[] rawTokens = TOKEN_SPLIT_PATTERN.split(query);
        return Arrays.stream(rawTokens)
                .map(String::trim)
                .filter(t -> t.length() >= 2 && !STOP_WORDS.contains(t))
                .collect(Collectors.toList());
    }

    private String extractAuthoredText(TrackTimelineEvent event) {
        if (event.getPayload() == null) {
            return null;
        }

        String jsonStr = event.getPayload().asString();
        if (jsonStr == null || jsonStr.isBlank()) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(jsonStr);
            if (root.has("text")) {
                return root.get("text").asText();
            } else if (root.has("description")) {
                return root.get("description").asText();
            } else if (root.has("note")) {
                return root.get("note").asText();
            } else if (root.isTextual()) {
                return root.asText();
            } else {
                return jsonStr;
            }
        } catch (Exception e) {
            return jsonStr;
        }
    }
}
