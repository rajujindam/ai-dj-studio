package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.track.RetrievedMetadataEntry;
import com.aidjstudio.ai_dj_studio.dto.track.TrackQnaRequest;
import com.aidjstudio.ai_dj_studio.dto.track.TrackQnaResponse;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.service.llm.LlmProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrackQnaService {

    public static final String CANNOT_ANSWER_MESSAGE = "Information not present in available metadata for this track.";
    private static final int MAX_EVIDENCE_LIMIT = 5;

    private final TrackRepository trackRepository;
    private final TrackMetadataRetrievalService retrievalService;
    private final LlmProvider llmProvider;

    /**
     * Answers a user question about a specific track using grounded timeline metadata.
     * If no evidence exists in metadata, returns an explicit cannot-answer response without calling the LLM.
     */
    public Mono<TrackQnaResponse> answerTrackQuestion(Long trackId, TrackQnaRequest request) {
        if (trackId == null) {
            return Mono.error(new IllegalArgumentException("Track id must not be null"));
        }
        if (request == null || request.getQuestion() == null || request.getQuestion().trim().isBlank()) {
            return Mono.error(new IllegalArgumentException("Question must not be blank"));
        }

        String question = request.getQuestion().trim();

        return trackRepository.findById(trackId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Track not found with id: " + trackId)))
                .flatMap(track -> retrievalService.retrieve(trackId, question, null, MAX_EVIDENCE_LIMIT)
                        .collectList()
                        .flatMap(evidence -> {
                            if (evidence.isEmpty()) {
                                log.debug("No metadata evidence found for track {} on query '{}'. Returning cannot-answer.", trackId, question);
                                return Mono.just(TrackQnaResponse.builder()
                                        .answer(CANNOT_ANSWER_MESSAGE)
                                        .sourceIds(Collections.emptyList())
                                        .sources(Collections.emptyList())
                                        .grounded(false)
                                        .build());
                            }

                            List<String> sourceIds = evidence.stream()
                                    .map(RetrievedMetadataEntry::getSourceId)
                                    .collect(Collectors.toList());

                            return llmProvider.generateGroundedAnswer(
                                    track.getTitle(),
                                    track.getArtist(),
                                    question,
                                    evidence
                            ).map(answer -> TrackQnaResponse.builder()
                                    .answer(answer)
                                    .sourceIds(sourceIds)
                                    .sources(evidence)
                                    .grounded(true)
                                    .build());
                        }));
    }
}
