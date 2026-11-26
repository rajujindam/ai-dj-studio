package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.entity.Interaction;
import com.aidjstudio.ai_dj_studio.repository.InteractionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for computing collaborative filtering recommendations from collected interactions and lounge co-listening.
 *
 * Algorithm Design:
 * - Explainable Item-Item Co-listening & Interaction Similarity:
 *   Tracks that are frequently listened to or liked together by the same users, or co-listened to in the same
 *   lounge sessions, exhibit high affinity.
 * - Cosine / Jaccard-style normalized affinity:
 *   Sim(i, j) = (CoListeners(i, j) + 0.5 * CoLounges(i, j)) / sqrt((Count(i) + 0.5 * LoungeCount(i)) * (Count(j) + 0.5 * LoungeCount(j)))
 * - User candidate scoring:
 *   Aggregates the user's historical positive track interactions weighted by interaction signal strength
 *   (liked = 2.5, completed = 2.0, standard play >= 30s = 1.0).
 * - Cold-start & sparsity handling:
 *   Returns 0.0 with clear explanatory diagnostics when the user has no interaction history or a candidate
 *   has not yet been co-listened to.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollaborativeFilteringService {

    private final InteractionRepository interactionRepository;

    public record CollaborativeScoreResult(
            double score,
            String reason,
            int coListeningCount,
            boolean hasSignal
    ) {}

    /**
     * Reactively computes collaborative filtering scores for all candidate tracks relative to the target user.
     */
    public Mono<Map<Long, CollaborativeScoreResult>> computeCollaborativeScores(
            Long targetUserId,
            Collection<Long> candidateTrackIds
    ) {
        if (targetUserId == null || candidateTrackIds == null || candidateTrackIds.isEmpty()) {
            return Mono.just(Collections.emptyMap());
        }

        return interactionRepository.findAll()
                .collectList()
                .map(allInteractions -> computeCollaborativeScoresFromInteractions(targetUserId, candidateTrackIds, allInteractions));
    }

    /**
     * Pure, explainable computation of collaborative scores from an interaction list.
     * Can be invoked directly by the offline evaluation harness without requiring database access.
     */
    public Map<Long, CollaborativeScoreResult> computeCollaborativeScoresFromInteractions(
            Long targetUserId,
            Collection<Long> candidateTrackIds,
            List<Interaction> allInteractions
    ) {
        if (candidateTrackIds == null || candidateTrackIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, CollaborativeScoreResult> results = new HashMap<>();

        if (allInteractions == null || allInteractions.isEmpty()) {
            for (Long candId : candidateTrackIds) {
                results.put(candId, new CollaborativeScoreResult(0.0, "No interaction history available", 0, false));
            }
            return results;
        }

        // 1. Identify target user's positive interactions
        List<Interaction> userInteractions = allInteractions.stream()
                .filter(i -> Objects.equals(i.getUserId(), targetUserId))
                .filter(this::isPositiveInteraction)
                .toList();

        if (userInteractions.isEmpty()) {
            // Cold-start user
            for (Long candId : candidateTrackIds) {
                results.put(candId, new CollaborativeScoreResult(0.0, "Cold start user: no positive interaction history", 0, false));
            }
            return results;
        }

        // Weight per track for the target user (likes have higher weight than passive plays)
        Map<Long, Double> userTrackWeights = new HashMap<>();
        for (Interaction in : userInteractions) {
            double weight = computeInteractionWeight(in);
            userTrackWeights.merge(in.getTrackId(), weight, Double::sum);
        }

        // 2. Build track -> user set and track -> lounge set for all positive interactions
        Map<Long, Set<Long>> trackUsers = new HashMap<>();
        Map<Long, Set<Long>> trackLounges = new HashMap<>();

        for (Interaction in : allInteractions) {
            if (!isPositiveInteraction(in)) {
                continue;
            }
            Long trackId = in.getTrackId();
            if (trackId != null) {
                if (in.getUserId() != null) {
                    trackUsers.computeIfAbsent(trackId, k -> new HashSet<>()).add(in.getUserId());
                }
                if (in.getLoungeId() != null) {
                    trackLounges.computeIfAbsent(trackId, k -> new HashSet<>()).add(in.getLoungeId());
                }
            }
        }

        // 3. For each candidate track, compute affinity to user's listened tracks
        for (Long candTrackId : candidateTrackIds) {
            Set<Long> candUsers = trackUsers.getOrDefault(candTrackId, Collections.emptySet());
            Set<Long> candLounges = trackLounges.getOrDefault(candTrackId, Collections.emptySet());

            if (candUsers.isEmpty() && candLounges.isEmpty()) {
                results.put(candTrackId, new CollaborativeScoreResult(
                        0.0,
                        "Candidate track has no co-listening history (sparse item)",
                        0,
                        false
                ));
                continue;
            }

            double totalWeightedSimilarity = 0.0;
            double totalUserWeight = 0.0;
            int maxSharedUsers = 0;
            Long mostSimilarSeedTrackId = null;
            double highestSimilarity = 0.0;

            for (Map.Entry<Long, Double> entry : userTrackWeights.entrySet()) {
                Long seedTrackId = entry.getKey();
                Double userWeight = entry.getValue();

                if (Objects.equals(seedTrackId, candTrackId)) {
                    continue; // Skip comparing candidate with itself
                }

                Set<Long> seedUsers = trackUsers.getOrDefault(seedTrackId, Collections.emptySet());
                Set<Long> seedLounges = trackLounges.getOrDefault(seedTrackId, Collections.emptySet());

                // Calculate overlap
                int sharedUsers = countIntersection(seedUsers, candUsers);
                int sharedLounges = countIntersection(seedLounges, candLounges);

                if (sharedUsers > maxSharedUsers) {
                    maxSharedUsers = sharedUsers;
                }

                if (sharedUsers == 0 && sharedLounges == 0) {
                    continue;
                }

                double denom = Math.sqrt(
                        (seedUsers.size() + 0.5 * seedLounges.size()) *
                        (candUsers.size() + 0.5 * candLounges.size())
                );

                if (denom > 0) {
                    double similarity = (sharedUsers + (0.5 * sharedLounges)) / denom;
                    similarity = Math.min(1.0, Math.max(0.0, similarity));

                    totalWeightedSimilarity += similarity * userWeight;
                    totalUserWeight += userWeight;

                    if (similarity > highestSimilarity) {
                        highestSimilarity = similarity;
                        mostSimilarSeedTrackId = seedTrackId;
                    }
                }
            }

            if (totalUserWeight > 0.0 && totalWeightedSimilarity > 0.0) {
                double rawScore = totalWeightedSimilarity / totalUserWeight;
                double normalizedScore = Math.min(1.0, Math.max(0.0, round4(rawScore)));

                String reason = String.format(
                        "Co-listening affinity %.2f (shared with %d listeners across tracks, highest similarity %.2f with seed #%d)",
                        normalizedScore,
                        maxSharedUsers,
                        highestSimilarity,
                        mostSimilarSeedTrackId != null ? mostSimilarSeedTrackId : 0L
                );

                results.put(candTrackId, new CollaborativeScoreResult(normalizedScore, reason, maxSharedUsers, true));
            } else {
                results.put(candTrackId, new CollaborativeScoreResult(
                        0.0,
                        "No co-listening overlap between candidate and user history",
                        0,
                        false
                ));
            }
        }

        return results;
    }

    private <T> int countIntersection(Set<T> set1, Set<T> set2) {
        if (set1 == null || set2 == null || set1.isEmpty() || set2.isEmpty()) {
            return 0;
        }
        int count = 0;
        Set<T> smaller = set1.size() <= set2.size() ? set1 : set2;
        Set<T> larger = set1.size() <= set2.size() ? set2 : set1;
        for (T item : smaller) {
            if (larger.contains(item)) {
                count++;
            }
        }
        return count;
    }

    public boolean isPositiveInteraction(Interaction interaction) {
        if (interaction == null) {
            return false;
        }
        return Boolean.TRUE.equals(interaction.getLiked())
                || Boolean.TRUE.equals(interaction.getCompleted())
                || (interaction.getPlayedSeconds() != null && interaction.getPlayedSeconds() >= 30);
    }

    private double computeInteractionWeight(Interaction in) {
        double weight = 1.0;
        if (Boolean.TRUE.equals(in.getLiked())) {
            weight += 1.5;
        }
        if (Boolean.TRUE.equals(in.getCompleted())) {
            weight += 1.0;
        }
        return weight;
    }

    private double round4(double val) {
        return Math.round(val * 10000.0) / 10000.0;
    }
}
