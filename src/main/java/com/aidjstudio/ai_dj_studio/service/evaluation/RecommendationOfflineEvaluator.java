package com.aidjstudio.ai_dj_studio.service.evaluation;

import com.aidjstudio.ai_dj_studio.dto.recommendation.RecommendedTrackResponse;
import com.aidjstudio.ai_dj_studio.dto.recommendation.UserTasteProfile;
import com.aidjstudio.ai_dj_studio.entity.Interaction;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.service.CollaborativeFilteringService;
import com.aidjstudio.ai_dj_studio.service.ContentBasedRecommendationService;
import com.aidjstudio.ai_dj_studio.service.HybridRecommendationService;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Offline Evaluation Harness for Hybrid Recommendations (F25 / PRD Section 1.3 & 6.3).
 *
 * Evaluates Precision@5 against a held-out test split from a generated interaction dataset.
 *
 * Dataset Generation & Split:
 * - 3 distinct music clusters:
 *     1. EDM / Progressive House (124-128 BPM, Camelot 8A/9A, Energy ~0.80, Valence ~0.60)
 *     2. Lo-Fi Chill / Downtempo (75-85 BPM, Camelot 4A/5A, Energy ~0.35, Valence ~0.45)
 *     3. Melodic Techno / Peak-Time (130-134 BPM, Camelot 6A/7A, Energy ~0.90, Valence ~0.50)
 * - Users with affinity to these clusters interact in solo sessions and collaborative lounges.
 * - Train / Held-Out Test Split:
 *     Interactions per cluster user are partitioned into training interactions and held-out test tracks.
 *     Ground truth contains sufficient held-out positive tracks per user to evaluate Precision@5.
 * - PRD KPI: Target Precision@5 >= 0.60 on the held-out dataset.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendationOfflineEvaluator {

    private final ContentBasedRecommendationService contentRecommendationService;
    private final CollaborativeFilteringService collaborativeFilteringService;
    private final HybridRecommendationService hybridRecommendationService;

    @Data
    @Builder
    public static class EvaluationResult {
        private int k;
        private int evaluatedUsers;
        private double precisionAtK;
        private double hitRateAtK;
        private Map<Long, Double> perUserPrecision;
        private Map<Long, List<Long>> perUserRecommendations;
        private Map<Long, Set<Long>> heldOutGroundTruth;
        private String datasetSummary;
    }

    @Data
    @Builder
    public static class BenchmarkDataset {
        private List<Track> catalog;
        private List<Interaction> trainInteractions;
        private Map<Long, Set<Long>> heldOutTestInteractions;
        private List<Long> testUserIds;
    }

    /**
     * Executes the offline Precision@K evaluation benchmark.
     */
    public EvaluationResult evaluatePrecisionAtK(int k) {
        BenchmarkDataset dataset = generateBenchmarkDataset();
        return runEvaluationOnDataset(dataset, k);
    }

    /**
     * Runs evaluation on a provided dataset.
     */
    public EvaluationResult runEvaluationOnDataset(BenchmarkDataset dataset, int k) {
        int effectiveK = Math.max(1, k);
        List<Track> catalog = dataset.getCatalog();
        List<Interaction> trainInteractions = dataset.getTrainInteractions();
        Map<Long, Set<Long>> groundTruth = dataset.getHeldOutTestInteractions();
        List<Long> testUsers = dataset.getTestUserIds();

        Map<Long, Double> perUserPrecision = new HashMap<>();
        Map<Long, List<Long>> perUserRecs = new HashMap<>();
        double totalPrecision = 0.0;
        int hitCount = 0;

        for (Long userId : testUsers) {
            Set<Long> heldOutTracks = groundTruth.getOrDefault(userId, Collections.emptySet());
            if (heldOutTracks.isEmpty()) {
                continue;
            }

            // 1. Build taste profile from trainInteractions only
            UserTasteProfile tasteProfile = buildProfileFromTrainInteractions(userId, trainInteractions, catalog);

            // 2. Identify tracks the user already interacted with in training (to exclude them from recommendations)
            Set<Long> trainInteractedTracks = trainInteractions.stream()
                    .filter(i -> Objects.equals(i.getUserId(), userId))
                    .map(Interaction::getTrackId)
                    .collect(Collectors.toSet());

            List<Track> candidateTracks = catalog.stream()
                    .filter(t -> !trainInteractedTracks.contains(t.getId()))
                    .toList();

            List<Long> candidateIds = candidateTracks.stream().map(Track::getId).toList();

            // 3. Compute collaborative scores from trainInteractions only
            Map<Long, CollaborativeFilteringService.CollaborativeScoreResult> collabScores =
                    collaborativeFilteringService.computeCollaborativeScoresFromInteractions(
                            userId, candidateIds, trainInteractions
                    );

            // 4. Rank using the hybrid recommender
            List<RecommendedTrackResponse> recommendations = hybridRecommendationService.rankHybridCandidates(
                    tasteProfile,
                    candidateTracks,
                    collabScores,
                    effectiveK,
                    null
            );

            List<Long> recommendedTrackIds = recommendations.stream()
                    .map(RecommendedTrackResponse::getTrackId)
                    .limit(effectiveK)
                    .toList();

            perUserRecs.put(userId, recommendedTrackIds);

            // 5. Measure Precision@K
            long hits = recommendedTrackIds.stream()
                    .filter(heldOutTracks::contains)
                    .count();

            double userPrecision = (double) hits / effectiveK;
            perUserPrecision.put(userId, userPrecision);
            totalPrecision += userPrecision;

            if (hits > 0) {
                hitCount++;
            }
        }

        int evaluatedUsers = testUsers.size();
        double meanPrecision = evaluatedUsers > 0 ? totalPrecision / evaluatedUsers : 0.0;
        double hitRate = evaluatedUsers > 0 ? (double) hitCount / evaluatedUsers : 0.0;

        double roundedPrecision = Math.round(meanPrecision * 1000.0) / 1000.0;
        double roundedHitRate = Math.round(hitRate * 1000.0) / 1000.0;

        String summary = String.format(
                "Benchmark dataset: %d tracks, %d training interactions, %d held-out test users. Precision@%d: %.3f (Target >= 0.60), HitRate@%d: %.3f",
                catalog.size(), trainInteractions.size(), evaluatedUsers, effectiveK, roundedPrecision, effectiveK, roundedHitRate
        );

        log.info(summary);

        return EvaluationResult.builder()
                .k(effectiveK)
                .evaluatedUsers(evaluatedUsers)
                .precisionAtK(roundedPrecision)
                .hitRateAtK(roundedHitRate)
                .perUserPrecision(perUserPrecision)
                .perUserRecommendations(perUserRecs)
                .heldOutGroundTruth(groundTruth)
                .datasetSummary(summary)
                .build();
    }

    /**
     * Synthesizes a structured benchmark catalog and train/held-out test interaction split.
     */
    public BenchmarkDataset generateBenchmarkDataset() {
        List<Track> catalog = new ArrayList<>();

        // Cluster 1: EDM / House (Tracks 101 to 110)
        for (int i = 1; i <= 10; i++) {
            catalog.add(new Track((long) (100 + i), "EDM Anthem " + i, "Artist A", 190, "http://asset/" + (100 + i),
                    BigDecimal.valueOf(124.0 + (i % 3)), (i % 2 == 0 ? "8A" : "9A"),
                    BigDecimal.valueOf(0.80 + ((i % 3) * 0.03)), BigDecimal.valueOf(0.60 + ((i % 3) * 0.03))));
        }

        // Cluster 2: Lo-Fi Chill (Tracks 201 to 210)
        for (int i = 1; i <= 10; i++) {
            catalog.add(new Track((long) (200 + i), "LoFi Drift " + i, "Artist B", 160, "http://asset/" + (200 + i),
                    BigDecimal.valueOf(80.0 + (i % 4)), (i % 2 == 0 ? "4A" : "5A"),
                    BigDecimal.valueOf(0.35 + ((i % 2) * 0.03)), BigDecimal.valueOf(0.45 + ((i % 2) * 0.03))));
        }

        // Cluster 3: Techno / Peak-Time (Tracks 301 to 310)
        for (int i = 1; i <= 10; i++) {
            catalog.add(new Track((long) (300 + i), "Dark Techno " + i, "Artist C", 210, "http://asset/" + (300 + i),
                    BigDecimal.valueOf(132.0 + (i % 2)), (i % 2 == 0 ? "6A" : "7A"),
                    BigDecimal.valueOf(0.90 + ((i % 2) * 0.03)), BigDecimal.valueOf(0.50 + ((i % 2) * 0.03))));
        }

        List<Interaction> allInteractions = new ArrayList<>();
        Map<Long, Set<Long>> heldOutInteractions = new HashMap<>();
        List<Long> testUsers = List.of(1L, 2L, 3L, 4L, 5L, 6L);

        LocalDateTime now = LocalDateTime.now();

        // Users 1, 2, 3 love EDM / House (Cluster 1, Tracks 101-108)
        for (Long uid : List.of(1L, 2L, 3L)) {
            List<Long> clusterTracks = List.of(101L, 102L, 103L, 104L, 105L, 106L, 107L, 108L);
            // 4 tracks in train, 4 tracks in held-out test
            for (int idx = 0; idx < clusterTracks.size(); idx++) {
                Long trackId = clusterTracks.get(idx);
                boolean isTrain = idx < 4;
                Interaction in = Interaction.builder()
                        .userId(uid)
                        .trackId(trackId)
                        .loungeId(10L) // Co-listened in Lounge 10
                        .playedSeconds(180)
                        .completed(true)
                        .liked(true)
                        .createdAt(now.minusHours(clusterTracks.size() - idx))
                        .build();

                if (isTrain) {
                    allInteractions.add(in);
                } else {
                    heldOutInteractions.computeIfAbsent(uid, k -> new HashSet<>()).add(trackId);
                }
            }
        }

        // Users 4, 5, 6 love Lo-Fi Chill (Cluster 2, Tracks 201-208)
        for (Long uid : List.of(4L, 5L, 6L)) {
            List<Long> clusterTracks = List.of(201L, 202L, 203L, 204L, 205L, 206L, 207L, 208L);
            for (int idx = 0; idx < clusterTracks.size(); idx++) {
                Long trackId = clusterTracks.get(idx);
                boolean isTrain = idx < 4;
                Interaction in = Interaction.builder()
                        .userId(uid)
                        .trackId(trackId)
                        .loungeId(20L) // Co-listened in Lounge 20
                        .playedSeconds(160)
                        .completed(true)
                        .liked(true)
                        .createdAt(now.minusHours(clusterTracks.size() - idx))
                        .build();

                if (isTrain) {
                    allInteractions.add(in);
                } else {
                    heldOutInteractions.computeIfAbsent(uid, k -> new HashSet<>()).add(trackId);
                }
            }
        }

        return BenchmarkDataset.builder()
                .catalog(catalog)
                .trainInteractions(allInteractions)
                .heldOutTestInteractions(heldOutInteractions)
                .testUserIds(testUsers)
                .build();
    }

    private UserTasteProfile buildProfileFromTrainInteractions(
            Long userId,
            List<Interaction> trainInteractions,
            List<Track> catalog
    ) {
        Map<Long, Track> trackMap = catalog.stream().collect(Collectors.toMap(Track::getId, t -> t));
        List<Interaction> userTrainInteractions = trainInteractions.stream()
                .filter(i -> Objects.equals(i.getUserId(), userId))
                .filter(collaborativeFilteringService::isPositiveInteraction)
                .toList();

        if (userTrainInteractions.isEmpty()) {
            return UserTasteProfile.builder()
                    .userId(userId)
                    .averageBpm(ContentBasedRecommendationService.DEFAULT_BPM)
                    .dominantCamelotKey(ContentBasedRecommendationService.DEFAULT_CAMELOT_KEY)
                    .averageEnergy(ContentBasedRecommendationService.DEFAULT_ENERGY)
                    .averageValence(ContentBasedRecommendationService.DEFAULT_VALENCE)
                    .positiveInteractionCount(0)
                    .coldStart(true)
                    .build();
        }

        double totalWeight = 0.0;
        double weightedBpm = 0.0;
        double weightedEnergy = 0.0;
        double weightedValence = 0.0;
        Map<String, Double> keyWeights = new HashMap<>();

        for (Interaction in : userTrainInteractions) {
            Track t = trackMap.get(in.getTrackId());
            if (t == null) continue;

            double weight = 1.0;
            if (Boolean.TRUE.equals(in.getLiked())) weight += 1.5;
            if (Boolean.TRUE.equals(in.getCompleted())) weight += 1.0;

            totalWeight += weight;
            weightedBpm += (t.getBpm() != null ? t.getBpm().doubleValue() : 124.0) * weight;
            weightedEnergy += (t.getEnergy() != null ? t.getEnergy().doubleValue() : 0.70) * weight;
            weightedValence += (t.getValence() != null ? t.getValence().doubleValue() : 0.50) * weight;

            String key = t.getCamelotKey() != null ? t.getCamelotKey() : "8A";
            keyWeights.put(key, keyWeights.getOrDefault(key, 0.0) + weight);
        }

        String dominantKey = keyWeights.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("8A");

        return UserTasteProfile.builder()
                .userId(userId)
                .averageBpm(BigDecimal.valueOf(weightedBpm / totalWeight))
                .dominantCamelotKey(dominantKey)
                .averageEnergy(BigDecimal.valueOf(weightedEnergy / totalWeight))
                .averageValence(BigDecimal.valueOf(weightedValence / totalWeight))
                .positiveInteractionCount(userTrainInteractions.size())
                .coldStart(false)
                .build();
    }
}
