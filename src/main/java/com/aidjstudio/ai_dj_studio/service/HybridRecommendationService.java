package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.recommendation.RecommendationRailResponse;
import com.aidjstudio.ai_dj_studio.dto.recommendation.RecommendationScoreBreakdown;
import com.aidjstudio.ai_dj_studio.dto.recommendation.RecommendedTrackResponse;
import com.aidjstudio.ai_dj_studio.dto.recommendation.UserTasteProfile;
import com.aidjstudio.ai_dj_studio.entity.Interaction;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.repository.InteractionRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/**
 * F25: Hybrid Recommendation Service combining content-based acoustic similarity (F24)
 * with collaborative filtering from co-listening and interaction patterns.
 *
 * Weighting Strategy:
 * - Balanced Hybrid: 50% Content-based + 50% Collaborative filtering.
 * - Dynamic Cold-Start Adaptation:
 *   When the user has no interaction history or collaborative data is absent, the engine
 *   gracefully adapts by allocating 100% weight to the content profile.
 * - Fully Explainable:
 *   Every recommended track carries a detailed breakdown showing both the acoustic fit
 *   (BPM, key, energy, valence) and the co-listening overlap from other users.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HybridRecommendationService {

    public static final double DEFAULT_CONTENT_WEIGHT = 0.50;
    public static final double DEFAULT_COLLAB_WEIGHT = 0.50;

    private final TrackRepository trackRepository;
    private final InteractionRepository interactionRepository;
    private final ContentBasedRecommendationService contentRecommendationService;
    private final CollaborativeFilteringService collaborativeFilteringService;

    /**
     * Generates hybrid personalized recommendations for the authenticated user.
     */
    public Mono<RecommendationRailResponse> getHybridPersonalizedRecommendations(
            Long userId,
            int limit,
            boolean excludeInteracted
    ) {
        if (userId == null) {
            return Mono.error(new IllegalArgumentException("User ID cannot be null"));
        }

        int effectiveLimit = Math.max(1, Math.min(limit, 50));

        return contentRecommendationService.getUserTasteProfile(userId)
                .flatMap(tasteProfile ->
                    interactionRepository.findByUserIdOrderByCreatedAtDesc(userId)
                            .collectList()
                            .flatMap(interactions -> {
                                Set<Long> interactedTrackIds = excludeInteracted
                                        ? interactions.stream()
                                            .filter(contentRecommendationService::isPositiveInteraction)
                                            .map(Interaction::getTrackId)
                                            .collect(Collectors.toSet())
                                        : Collections.emptySet();

                                return trackRepository.findAll()
                                        .collectList()
                                        .flatMap(allTracks -> {
                                            List<Track> candidatePool = allTracks.stream()
                                                    .filter(t -> !interactedTrackIds.contains(t.getId()))
                                                    .toList();

                                            List<Track> finalCandidates = candidatePool.isEmpty() ? allTracks : candidatePool;
                                            List<Long> candidateIds = finalCandidates.stream().map(Track::getId).toList();

                                            return collaborativeFilteringService.computeCollaborativeScores(userId, candidateIds)
                                                    .map(collabScores -> {
                                                        List<RecommendedTrackResponse> ranked = rankHybridCandidates(
                                                                tasteProfile,
                                                                finalCandidates,
                                                                collabScores,
                                                                effectiveLimit,
                                                                null
                                                        );

                                                        return RecommendationRailResponse.builder()
                                                                .strategy("HYBRID_PERSONALIZED")
                                                                .userId(userId)
                                                                .tasteProfile(tasteProfile)
                                                                .totalRecommendations(ranked.size())
                                                                .tracks(ranked)
                                                                .build();
                                                    });
                                        });
                            })
                );
    }

    /**
     * Ranks candidate tracks by combining content score and collaborative score.
     */
    public List<RecommendedTrackResponse> rankHybridCandidates(
            UserTasteProfile tasteProfile,
            List<Track> candidates,
            Map<Long, CollaborativeFilteringService.CollaborativeScoreResult> collabScores,
            int limit,
            Double customCollabWeight
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        boolean isColdStart = tasteProfile == null || tasteProfile.isColdStart();
        boolean hasAnyCollabSignal = collabScores != null && collabScores.values().stream().anyMatch(CollaborativeFilteringService.CollaborativeScoreResult::hasSignal);

        double collabWeight;
        double contentWeight;

        if (isColdStart || !hasAnyCollabSignal) {
            // Cold start: 100% content fallback
            collabWeight = 0.0;
            contentWeight = 1.0;
        } else if (customCollabWeight != null) {
            collabWeight = Math.max(0.0, Math.min(1.0, customCollabWeight));
            contentWeight = round4(1.0 - collabWeight);
        } else {
            collabWeight = DEFAULT_COLLAB_WEIGHT;
            contentWeight = DEFAULT_CONTENT_WEIGHT;
        }

        List<RecommendedTrackResponse> scored = new ArrayList<>();

        for (Track candidate : candidates) {
            if (candidate == null) {
                continue;
            }

            // 1. Content breakdown (acoustic fit)
            RecommendationScoreBreakdown contentBreakdown = contentRecommendationService.scoreCandidate(tasteProfile, candidate);
            double contentScore = contentBreakdown.getTotalScore();

            // 2. Collaborative score (co-listening fit)
            CollaborativeFilteringService.CollaborativeScoreResult collabResult = collabScores != null
                    ? collabScores.getOrDefault(candidate.getId(), new CollaborativeFilteringService.CollaborativeScoreResult(0.0, "No collaborative signal", 0, false))
                    : new CollaborativeFilteringService.CollaborativeScoreResult(0.0, "No collaborative signal", 0, false);

            double collabScore = collabResult.score();

            // 3. Combined hybrid score
            double contentWeighted = round4(contentScore * contentWeight);
            double collabWeighted = round4(collabScore * collabWeight);
            double hybridScore = round4(contentWeighted + collabWeighted);

            String combinedReason;
            if (collabWeight == 0.0) {
                combinedReason = String.format("Cold-start fallback: Content fit %.2f (%s)", contentScore, contentBreakdown.getReason());
            } else {
                combinedReason = String.format(
                        "Hybrid score %.2f: Content 50%% (%.2f: %s) + Collaborative 50%% (%.2f: %s)",
                        hybridScore, contentScore, contentBreakdown.getKeyRelation(), collabScore, collabResult.reason()
                );
            }

            // Enrich the score breakdown with hybrid components
            RecommendationScoreBreakdown hybridBreakdown = RecommendationScoreBreakdown.builder()
                    .totalScore(hybridScore)
                    // Content metrics
                    .bpmScore(contentBreakdown.getBpmScore())
                    .bpmWeight(contentBreakdown.getBpmWeight())
                    .bpmWeightedScore(contentBreakdown.getBpmWeightedScore())
                    .bpmDelta(contentBreakdown.getBpmDelta())
                    .keyScore(contentBreakdown.getKeyScore())
                    .keyWeight(contentBreakdown.getKeyWeight())
                    .keyWeightedScore(contentBreakdown.getKeyWeightedScore())
                    .keyRelation(contentBreakdown.getKeyRelation())
                    .energyScore(contentBreakdown.getEnergyScore())
                    .energyWeight(contentBreakdown.getEnergyWeight())
                    .energyWeightedScore(contentBreakdown.getEnergyWeightedScore())
                    .energyDelta(contentBreakdown.getEnergyDelta())
                    .valenceScore(contentBreakdown.getValenceScore())
                    .valenceWeight(contentBreakdown.getValenceWeight())
                    .valenceWeightedScore(contentBreakdown.getValenceWeightedScore())
                    .valenceDelta(contentBreakdown.getValenceDelta())
                    // Hybrid metrics
                    .contentScore(contentScore)
                    .contentWeight(contentWeight)
                    .contentWeightedScore(contentWeighted)
                    .collaborativeScore(collabScore)
                    .collaborativeWeight(collabWeight)
                    .collaborativeWeightedScore(collabWeighted)
                    .collaborativeReason(collabResult.reason())
                    .coListeningCount(collabResult.coListeningCount())
                    .reason(combinedReason)
                    .build();

            scored.add(RecommendedTrackResponse.builder()
                    .trackId(candidate.getId())
                    .title(candidate.getTitle())
                    .artist(candidate.getArtist())
                    .durationSeconds(candidate.getDurationSeconds())
                    .bpm(candidate.getBpm())
                    .camelotKey(candidate.getCamelotKey())
                    .energy(candidate.getEnergy())
                    .valence(candidate.getValence())
                    .similarityScore(hybridScore)
                    .reason(combinedReason)
                    .scoreBreakdown(hybridBreakdown)
                    .build());
        }

        // Deterministic sorting: hybridScore desc -> collabScore desc -> contentScore desc -> trackId asc
        scored.sort((a, b) -> {
            int scoreCmp = Double.compare(b.getSimilarityScore(), a.getSimilarityScore());
            if (scoreCmp != 0) {
                return scoreCmp;
            }
            double bCollab = b.getScoreBreakdown().getCollaborativeScore() != null ? b.getScoreBreakdown().getCollaborativeScore() : 0.0;
            double aCollab = a.getScoreBreakdown().getCollaborativeScore() != null ? a.getScoreBreakdown().getCollaborativeScore() : 0.0;
            int collabCmp = Double.compare(bCollab, aCollab);
            if (collabCmp != 0) {
                return collabCmp;
            }
            double bContent = b.getScoreBreakdown().getContentScore() != null ? b.getScoreBreakdown().getContentScore() : 0.0;
            double aContent = a.getScoreBreakdown().getContentScore() != null ? a.getScoreBreakdown().getContentScore() : 0.0;
            int contentCmp = Double.compare(bContent, aContent);
            if (contentCmp != 0) {
                return contentCmp;
            }
            return Long.compare(a.getTrackId(), b.getTrackId());
        });

        List<RecommendedTrackResponse> limited = scored.stream().limit(limit).toList();
        for (int i = 0; i < limited.size(); i++) {
            limited.get(i).setRank(i + 1);
        }

        return limited;
    }

    private double round4(double val) {
        return Math.round(val * 10000.0) / 10000.0;
    }
}
