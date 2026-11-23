package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.acoustic.AcousticMatchResult;
import com.aidjstudio.ai_dj_studio.dto.acoustic.RankedCandidate;
import com.aidjstudio.ai_dj_studio.dto.track.RetrievedMetadataEntry;
import com.aidjstudio.ai_dj_studio.dto.transition.TransitionDecisionRequest;
import com.aidjstudio.ai_dj_studio.dto.transition.TransitionDecisionResponse;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.service.llm.LlmProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransitionDecisionService {

    private final TrackRepository trackRepository;
    private final CandidateRankingService candidateRankingService;
    private final TrackMetadataRetrievalService metadataRetrievalService;
    private final LlmProvider llmProvider;

    /**
     * Evaluates candidate tracks for a transition from the current track,
     * enforces hard acoustic matching rules (BPM +/-8%, Camelot adjacency/relative key),
     * ranks valid candidates, retrieves authored metadata, and generates grounded transition banter.
     *
     * @param currentTrackId the ID of the playing track
     * @param request        candidate IDs and optional energy/recency context
     * @return Mono emitting the transition decision response
     */
    public Mono<TransitionDecisionResponse> evaluateTransition(Long currentTrackId, TransitionDecisionRequest request) {
        if (currentTrackId == null) {
            return Mono.error(new IllegalArgumentException("Current track ID must not be null"));
        }

        return trackRepository.findById(currentTrackId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Track not found with id: " + currentTrackId)))
                .flatMap(currentTrack -> processTransition(currentTrack, request));
    }

    private Mono<TransitionDecisionResponse> processTransition(Track currentTrack, TransitionDecisionRequest request) {
        List<Long> candidateIds = (request != null && request.getCandidateTrackIds() != null)
                ? request.getCandidateTrackIds()
                : Collections.emptyList();

        if (candidateIds.isEmpty()) {
            return Mono.just(buildNoSegueResponse(currentTrack, "No candidate track IDs provided."));
        }

        return trackRepository.findAllById(candidateIds)
                .collectList()
                .flatMap(candidates -> {
                    if (candidates.isEmpty()) {
                        return Mono.just(buildNoSegueResponse(
                                currentTrack,
                                "None of the provided candidate track IDs exist in the catalog."
                        ));
                    }

                    // Rank candidates - candidateRankingService internally evaluates hard acoustic rules (F10)
                    // and filters out any incompatible candidates completely.
                    List<Long> recentIds = (request.getRecentTrackIds() != null) ? request.getRecentTrackIds() : Collections.emptyList();
                    Double bpmTolerance = (request.getBpmTolerancePct() != null) ? request.getBpmTolerancePct() : AcousticMatcherService.DEFAULT_BPM_TOLERANCE_PCT;

                    List<RankedCandidate> ranked = candidateRankingService.rankCandidates(
                            currentTrack,
                            candidates,
                            recentIds,
                            request.getTargetEnergy(),
                            bpmTolerance
                    );

                    if (ranked.isEmpty()) {
                        // Safe, explicit no-segue result: None of the candidate tracks passed hard acoustic constraints
                        return Mono.just(buildNoSegueResponse(
                                currentTrack,
                                "No candidate track met harmonic and BPM segue constraints."
                        ));
                    }

                    RankedCandidate topCandidate = ranked.get(0);
                    Track selectedTrack = topCandidate.getTrack();
                    AcousticMatchResult matchResult = topCandidate.getMatchResult();

                    String explanation = (matchResult != null && matchResult.getReason() != null)
                            ? matchResult.getReason()
                            : "Candidate passed harmonic and tempo compatibility.";

                    // Retrieve authored timeline metadata for current and selected track
                    List<Long> trackIdsForMetadata = List.of(currentTrack.getId(), selectedTrack.getId());

                    return metadataRetrievalService.retrieveForTracks(trackIdsForMetadata, null, null, 10)
                            .collectList()
                            .flatMap(evidence -> {
                                List<String> sourceIds = evidence.stream()
                                        .map(RetrievedMetadataEntry::getSourceId)
                                        .toList();

                                return llmProvider.generateTransitionScript(currentTrack, selectedTrack, explanation, evidence)
                                        .onErrorResume(ex -> {
                                            log.warn("LLM banter generation failed, falling back to deterministic explanation: {}", ex.getMessage());
                                            String fallbackScript = String.format(
                                                    "Mixing from \"%s\" by %s into \"%s\" by %s. %s",
                                                    currentTrack.getTitle(),
                                                    currentTrack.getArtist(),
                                                    selectedTrack.getTitle(),
                                                    selectedTrack.getArtist(),
                                                    explanation
                                            );
                                            return Mono.just(fallbackScript);
                                        })
                                        .map(script -> TransitionDecisionResponse.builder()
                                                .seguePossible(true)
                                                .currentTrackId(currentTrack.getId())
                                                .currentTrackTitle(currentTrack.getTitle())
                                                .selectedTrackId(selectedTrack.getId())
                                                .selectedTrackTitle(selectedTrack.getTitle())
                                                .selectedTrackArtist(selectedTrack.getArtist())
                                                .bpmDelta(matchResult != null ? matchResult.getBpmDelta() : null)
                                                .camelotRelation(matchResult != null && matchResult.getHarmonicRelation() != null
                                                        ? matchResult.getHarmonicRelation().name()
                                                        : null)
                                                .rankingExplanation(explanation)
                                                .script(script)
                                                .sourceIds(sourceIds)
                                                .sources(evidence)
                                                .generatedAt(Instant.now())
                                                .build());
                            });
                });
    }

    private TransitionDecisionResponse buildNoSegueResponse(Track currentTrack, String reason) {
        return TransitionDecisionResponse.builder()
                .seguePossible(false)
                .currentTrackId(currentTrack.getId())
                .currentTrackTitle(currentTrack.getTitle())
                .selectedTrackId(null)
                .selectedTrackTitle(null)
                .selectedTrackArtist(null)
                .bpmDelta(null)
                .camelotRelation(null)
                .rankingExplanation(reason)
                .script("No valid segue possible with current candidates.")
                .sourceIds(Collections.emptyList())
                .sources(Collections.emptyList())
                .generatedAt(Instant.now())
                .build();
    }
}
