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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContentBasedRecommendationService {

    public static final double BPM_WEIGHT = 0.30;
    public static final double KEY_WEIGHT = 0.30;
    public static final double ENERGY_WEIGHT = 0.20;
    public static final double VALENCE_WEIGHT = 0.20;

    public static final double MAX_BPM_DELTA = 50.0;
    private static final Pattern CAMELOT_PATTERN = Pattern.compile("^(1[0-2]|[1-9])([ABab])$");

    // Cold-start fallback defaults
    public static final BigDecimal DEFAULT_BPM = BigDecimal.valueOf(124.0);
    public static final String DEFAULT_CAMELOT_KEY = "8A";
    public static final BigDecimal DEFAULT_ENERGY = BigDecimal.valueOf(0.70);
    public static final BigDecimal DEFAULT_VALENCE = BigDecimal.valueOf(0.50);

    private final TrackRepository trackRepository;
    private final InteractionRepository interactionRepository;
    private final AcousticMatcherService acousticMatcherService;

    /**
     * Computes the user's acoustic taste profile from their interaction history.
     * Uses positive interactions (liked, completed, or played >= 30s) weighted by interaction strength.
     * Falls back to catalog average or default values for cold-start users.
     */
    public Mono<UserTasteProfile> getUserTasteProfile(Long userId) {
        if (userId == null) {
            return Mono.error(new IllegalArgumentException("User ID cannot be null"));
        }

        return interactionRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .collectList()
                .flatMap(interactions -> {
                    List<Interaction> positiveInteractions = interactions.stream()
                            .filter(this::isPositiveInteraction)
                            .toList();

                    if (positiveInteractions.isEmpty()) {
                        return buildColdStartProfile(userId);
                    }

                    List<Long> trackIds = positiveInteractions.stream()
                            .map(Interaction::getTrackId)
                            .distinct()
                            .toList();

                    return trackRepository.findAllById(trackIds)
                            .collectMap(Track::getId)
                            .map(trackMap -> calculateProfileFromInteractions(userId, positiveInteractions, trackMap));
                });
    }

    /**
     * Generates personalized content-based recommendations for an authenticated user.
     */
    public Mono<RecommendationRailResponse> getPersonalizedRecommendations(
            Long userId,
            int limit,
            boolean excludeInteracted
    ) {
        int effectiveLimit = Math.max(1, Math.min(limit, 50));

        return getUserTasteProfile(userId)
                .flatMap(tasteProfile ->
                    interactionRepository.findByUserIdOrderByCreatedAtDesc(userId)
                            .collectList()
                            .flatMap(interactions -> {
                                Set<Long> interactedTrackIds = excludeInteracted
                                        ? interactions.stream()
                                            .filter(this::isPositiveInteraction)
                                            .map(Interaction::getTrackId)
                                            .collect(Collectors.toSet())
                                        : Collections.emptySet();

                                return trackRepository.findAll()
                                        .collectList()
                                        .map(allTracks -> {
                                            List<Track> candidatePool = allTracks.stream()
                                                    .filter(t -> !interactedTrackIds.contains(t.getId()))
                                                    .toList();

                                            // If exclusion leaves no tracks, gracefully fall back to all tracks
                                            List<Track> finalCandidates = candidatePool.isEmpty() ? allTracks : candidatePool;

                                            List<RecommendedTrackResponse> recommended = rankTracksForProfile(tasteProfile, finalCandidates, effectiveLimit);

                                            return RecommendationRailResponse.builder()
                                                    .strategy("CONTENT_BASED_PERSONALIZED")
                                                    .userId(userId)
                                                    .tasteProfile(tasteProfile)
                                                    .totalRecommendations(recommended.size())
                                                    .tracks(recommended)
                                                    .build();
                                        });
                            })
                );
    }

    /**
     * Generates seed-track based recommendations ("More like this track").
     */
    public Mono<RecommendationRailResponse> getTrackRecommendations(Long seedTrackId, int limit) {
        if (seedTrackId == null) {
            return Mono.error(new IllegalArgumentException("Seed track ID cannot be null"));
        }

        int effectiveLimit = Math.max(1, Math.min(limit, 50));

        return trackRepository.findById(seedTrackId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Track not found with id: " + seedTrackId)))
                .flatMap(seedTrack -> {
                    UserTasteProfile seedProfile = UserTasteProfile.builder()
                            .userId(null)
                            .averageBpm(seedTrack.getBpm() != null ? seedTrack.getBpm() : DEFAULT_BPM)
                            .dominantCamelotKey(seedTrack.getCamelotKey() != null ? seedTrack.getCamelotKey() : DEFAULT_CAMELOT_KEY)
                            .averageEnergy(seedTrack.getEnergy() != null ? seedTrack.getEnergy() : DEFAULT_ENERGY)
                            .averageValence(seedTrack.getValence() != null ? seedTrack.getValence() : DEFAULT_VALENCE)
                            .positiveInteractionCount(1)
                            .coldStart(false)
                            .build();

                    return trackRepository.findAll()
                            .collectList()
                            .map(allTracks -> {
                                List<Track> candidates = allTracks.stream()
                                        .filter(t -> !t.getId().equals(seedTrackId))
                                        .toList();

                                List<RecommendedTrackResponse> recommended = rankTracksForProfile(seedProfile, candidates, effectiveLimit);

                                return RecommendationRailResponse.builder()
                                        .strategy("CONTENT_BASED_SEED_TRACK")
                                        .seedTrackId(seedTrackId)
                                        .tasteProfile(seedProfile)
                                        .totalRecommendations(recommended.size())
                                        .tracks(recommended)
                                        .build();
                            });
                });
    }

    /**
     * Ranks candidate tracks against a target acoustic profile using the approved formula:
     * 30% BPM, 30% Camelot key closeness, 20% Energy, 20% Valence.
     */
    public List<RecommendedTrackResponse> rankTracksForProfile(
            UserTasteProfile profile,
            List<Track> candidates,
            int limit
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<RecommendedTrackResponse> scored = new ArrayList<>();

        for (Track candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            RecommendationScoreBreakdown breakdown = scoreCandidate(profile, candidate);

            scored.add(RecommendedTrackResponse.builder()
                    .trackId(candidate.getId())
                    .title(candidate.getTitle())
                    .artist(candidate.getArtist())
                    .durationSeconds(candidate.getDurationSeconds())
                    .bpm(candidate.getBpm())
                    .camelotKey(candidate.getCamelotKey())
                    .energy(candidate.getEnergy())
                    .valence(candidate.getValence())
                    .similarityScore(breakdown.getTotalScore())
                    .reason(breakdown.getReason())
                    .scoreBreakdown(breakdown)
                    .build());
        }

        // Deterministic sorting: totalScore desc -> keyScore desc -> energyScore desc -> trackId asc
        scored.sort((a, b) -> {
            int scoreCmp = Double.compare(b.getSimilarityScore(), a.getSimilarityScore());
            if (scoreCmp != 0) {
                return scoreCmp;
            }
            int keyCmp = Double.compare(b.getScoreBreakdown().getKeyScore(), a.getScoreBreakdown().getKeyScore());
            if (keyCmp != 0) {
                return keyCmp;
            }
            int energyCmp = Double.compare(b.getScoreBreakdown().getEnergyScore(), a.getScoreBreakdown().getEnergyScore());
            if (energyCmp != 0) {
                return energyCmp;
            }
            return Long.compare(a.getTrackId(), b.getTrackId());
        });

        List<RecommendedTrackResponse> limited = scored.stream().limit(limit).toList();
        for (int i = 0; i < limited.size(); i++) {
            limited.get(i).setRank(i + 1);
        }

        return limited;
    }

    /**
     * Calculates the 4-component content similarity breakdown between profile and candidate.
     */
    public RecommendationScoreBreakdown scoreCandidate(UserTasteProfile profile, Track candidate) {
        BigDecimal targetBpm = profile.getAverageBpm() != null ? profile.getAverageBpm() : DEFAULT_BPM;
        BigDecimal candBpm = candidate.getBpm() != null ? candidate.getBpm() : DEFAULT_BPM;

        // 1. BPM similarity (30%) - considers direct, half-time, and double-time multiples
        double effectiveBpmDelta = computeEffectiveBpmDelta(targetBpm.doubleValue(), candBpm.doubleValue());
        double bpmScore = Math.max(0.0, Math.min(1.0, 1.0 - (effectiveBpmDelta / MAX_BPM_DELTA)));
        double bpmWeighted = round4(bpmScore * BPM_WEIGHT);

        // 2. Camelot harmonic closeness (30%) - circle of fifths distance
        String targetKey = profile.getDominantCamelotKey() != null ? profile.getDominantCamelotKey() : DEFAULT_CAMELOT_KEY;
        String candKey = candidate.getCamelotKey() != null ? candidate.getCamelotKey() : DEFAULT_CAMELOT_KEY;
        HarmonicEvaluation harmonicEval = evaluateHarmonicCloseness(targetKey, candKey);
        double keyScore = harmonicEval.score();
        double keyWeighted = round4(keyScore * KEY_WEIGHT);

        // 3. Energy similarity (20%)
        BigDecimal targetEnergy = profile.getAverageEnergy() != null ? profile.getAverageEnergy() : DEFAULT_ENERGY;
        BigDecimal candEnergy = candidate.getEnergy() != null ? candidate.getEnergy() : DEFAULT_ENERGY;
        double energyDelta = Math.abs(candEnergy.doubleValue() - targetEnergy.doubleValue());
        double energyScore = Math.max(0.0, Math.min(1.0, 1.0 - energyDelta));
        double energyWeighted = round4(energyScore * ENERGY_WEIGHT);

        // 4. Valence similarity (20%)
        BigDecimal targetValence = profile.getAverageValence() != null ? profile.getAverageValence() : DEFAULT_VALENCE;
        BigDecimal candValence = candidate.getValence() != null ? candidate.getValence() : DEFAULT_VALENCE;
        double valenceDelta = Math.abs(candValence.doubleValue() - targetValence.doubleValue());
        double valenceScore = Math.max(0.0, Math.min(1.0, 1.0 - valenceDelta));
        double valenceWeighted = round4(valenceScore * VALENCE_WEIGHT);

        // Total normalized score
        double rawTotal = bpmWeighted + keyWeighted + energyWeighted + valenceWeighted;
        double totalScore = round4(Math.min(1.0, Math.max(0.0, rawTotal)));

        String reason = String.format(
                "Match score %.2f: BPM similarity %.2f (%s), Harmonic %s (%.2f), Energy fit %.2f, Valence fit %.2f",
                totalScore, bpmScore, harmonicEval.relationDescription(), harmonicEval.relationDescription(), keyScore, energyScore, valenceScore
        );

        return RecommendationScoreBreakdown.builder()
                .totalScore(totalScore)
                .bpmScore(round4(bpmScore))
                .bpmWeight(BPM_WEIGHT)
                .bpmWeightedScore(bpmWeighted)
                .bpmDelta(BigDecimal.valueOf(effectiveBpmDelta).setScale(2, RoundingMode.HALF_UP))
                .keyScore(round4(keyScore))
                .keyWeight(KEY_WEIGHT)
                .keyWeightedScore(keyWeighted)
                .keyRelation(harmonicEval.relationDescription())
                .energyScore(round4(energyScore))
                .energyWeight(ENERGY_WEIGHT)
                .energyWeightedScore(energyWeighted)
                .energyDelta(BigDecimal.valueOf(energyDelta).setScale(2, RoundingMode.HALF_UP))
                .valenceScore(round4(valenceScore))
                .valenceWeight(VALENCE_WEIGHT)
                .valenceWeightedScore(valenceWeighted)
                .valenceDelta(BigDecimal.valueOf(valenceDelta).setScale(2, RoundingMode.HALF_UP))
                .reason(reason)
                .build();
    }

    private double computeEffectiveBpmDelta(double targetBpm, double candBpm) {
        double direct = Math.abs(candBpm - targetBpm);
        double halfTime = Math.abs(candBpm - (targetBpm * 0.5));
        double doubleTime = Math.abs(candBpm - (targetBpm * 2.0));
        return Math.min(direct, Math.min(halfTime, doubleTime));
    }

    private record HarmonicEvaluation(double score, String relationDescription) {}

    private HarmonicEvaluation evaluateHarmonicCloseness(String key1, String key2) {
        String k1 = normalizeCamelot(key1);
        String k2 = normalizeCamelot(key2);

        Matcher m1 = CAMELOT_PATTERN.matcher(k1);
        Matcher m2 = CAMELOT_PATTERN.matcher(k2);
        if (!m1.matches() || !m2.matches()) {
            return new HarmonicEvaluation(0.0, "UNKNOWN_KEY");
        }

        int n1 = Integer.parseInt(m1.group(1));
        String l1 = m1.group(2).toUpperCase();
        int n2 = Integer.parseInt(m2.group(1));
        String l2 = m2.group(2).toUpperCase();

        int circleDistance = Math.min(Math.abs(n1 - n2), 12 - Math.abs(n1 - n2));
        boolean sameMode = l1.equals(l2);

        if (sameMode) {
            if (circleDistance == 0) {
                return new HarmonicEvaluation(1.0, "SAME_KEY (" + k1 + ")");
            }
            if (circleDistance == 1) {
                return new HarmonicEvaluation(0.85, "ADJACENT_KEY (" + k1 + "->" + k2 + ")");
            }
            double score = Math.max(0.0, 0.60 - ((circleDistance - 2) * 0.12));
            return new HarmonicEvaluation(score, "DISTANCE_" + circleDistance + " (" + k1 + "->" + k2 + ")");
        } else {
            if (circleDistance == 0) {
                return new HarmonicEvaluation(0.75, "RELATIVE_KEY (" + k1 + "->" + k2 + ")");
            }
            if (circleDistance == 1) {
                return new HarmonicEvaluation(0.60, "DIAGONAL_NEIGHBOR (" + k1 + "->" + k2 + ")");
            }
            double score = Math.max(0.0, 0.45 - ((circleDistance - 2) * 0.10));
            return new HarmonicEvaluation(score, "DISTANCE_" + circleDistance + "_CROSS_MODE (" + k1 + "->" + k2 + ")");
        }
    }

    private String normalizeCamelot(String key) {
        if (key == null || key.trim().isEmpty()) {
            return DEFAULT_CAMELOT_KEY;
        }
        try {
            return acousticMatcherService.validateAndNormalizeCamelotKey(key);
        } catch (Exception e) {
            return DEFAULT_CAMELOT_KEY;
        }
    }

    public boolean isPositiveInteraction(Interaction interaction) {
        if (interaction == null) {
            return false;
        }
        return Boolean.TRUE.equals(interaction.getLiked())
                || Boolean.TRUE.equals(interaction.getCompleted())
                || (interaction.getPlayedSeconds() != null && interaction.getPlayedSeconds() >= 30);
    }

    private UserTasteProfile calculateProfileFromInteractions(
            Long userId,
            List<Interaction> interactions,
            Map<Long, Track> trackMap
    ) {
        double totalWeight = 0.0;
        double weightedBpm = 0.0;
        double weightedEnergy = 0.0;
        double weightedValence = 0.0;
        Map<String, Double> keyWeights = new HashMap<>();

        for (Interaction in : interactions) {
            Track t = trackMap.get(in.getTrackId());
            if (t == null) {
                continue;
            }

            double weight = 1.0;
            if (Boolean.TRUE.equals(in.getLiked())) {
                weight += 1.5; // Likes are strong signals
            }
            if (Boolean.TRUE.equals(in.getCompleted())) {
                weight += 1.0; // Completions are solid signals
            }

            totalWeight += weight;

            double bpmVal = t.getBpm() != null ? t.getBpm().doubleValue() : DEFAULT_BPM.doubleValue();
            double energyVal = t.getEnergy() != null ? t.getEnergy().doubleValue() : DEFAULT_ENERGY.doubleValue();
            double valenceVal = t.getValence() != null ? t.getValence().doubleValue() : DEFAULT_VALENCE.doubleValue();

            weightedBpm += bpmVal * weight;
            weightedEnergy += energyVal * weight;
            weightedValence += valenceVal * weight;

            String key = normalizeCamelot(t.getCamelotKey());
            keyWeights.put(key, keyWeights.getOrDefault(key, 0.0) + weight);
        }

        if (totalWeight <= 0.0) {
            return buildDefaultTasteProfile(userId);
        }

        String dominantKey = keyWeights.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(DEFAULT_CAMELOT_KEY);

        return UserTasteProfile.builder()
                .userId(userId)
                .averageBpm(BigDecimal.valueOf(weightedBpm / totalWeight).setScale(2, RoundingMode.HALF_UP))
                .dominantCamelotKey(dominantKey)
                .averageEnergy(BigDecimal.valueOf(weightedEnergy / totalWeight).setScale(2, RoundingMode.HALF_UP))
                .averageValence(BigDecimal.valueOf(weightedValence / totalWeight).setScale(2, RoundingMode.HALF_UP))
                .positiveInteractionCount(interactions.size())
                .coldStart(false)
                .build();
    }

    private Mono<UserTasteProfile> buildColdStartProfile(Long userId) {
        return trackRepository.findAll()
                .collectList()
                .map(tracks -> {
                    if (tracks.isEmpty()) {
                        return buildDefaultTasteProfile(userId);
                    }
                    double avgBpm = tracks.stream()
                            .filter(t -> t.getBpm() != null)
                            .mapToDouble(t -> t.getBpm().doubleValue())
                            .average()
                            .orElse(DEFAULT_BPM.doubleValue());

                    double avgEnergy = tracks.stream()
                            .filter(t -> t.getEnergy() != null)
                            .mapToDouble(t -> t.getEnergy().doubleValue())
                            .average()
                            .orElse(DEFAULT_ENERGY.doubleValue());

                    double avgValence = tracks.stream()
                            .filter(t -> t.getValence() != null)
                            .mapToDouble(t -> t.getValence().doubleValue())
                            .average()
                            .orElse(DEFAULT_VALENCE.doubleValue());

                    Map<String, Long> keyCounts = tracks.stream()
                            .filter(t -> t.getCamelotKey() != null)
                            .collect(Collectors.groupingBy(t -> normalizeCamelot(t.getCamelotKey()), Collectors.counting()));

                    String dominantKey = keyCounts.entrySet().stream()
                            .max(Map.Entry.comparingByValue())
                            .map(Map.Entry::getKey)
                            .orElse(DEFAULT_CAMELOT_KEY);

                    return UserTasteProfile.builder()
                            .userId(userId)
                            .averageBpm(BigDecimal.valueOf(avgBpm).setScale(2, RoundingMode.HALF_UP))
                            .dominantCamelotKey(dominantKey)
                            .averageEnergy(BigDecimal.valueOf(avgEnergy).setScale(2, RoundingMode.HALF_UP))
                            .averageValence(BigDecimal.valueOf(avgValence).setScale(2, RoundingMode.HALF_UP))
                            .positiveInteractionCount(0)
                            .coldStart(true)
                            .build();
                });
    }

    private UserTasteProfile buildDefaultTasteProfile(Long userId) {
        return UserTasteProfile.builder()
                .userId(userId)
                .averageBpm(DEFAULT_BPM)
                .dominantCamelotKey(DEFAULT_CAMELOT_KEY)
                .averageEnergy(DEFAULT_ENERGY)
                .averageValence(DEFAULT_VALENCE)
                .positiveInteractionCount(0)
                .coldStart(true)
                .build();
    }

    private double round4(double val) {
        return BigDecimal.valueOf(val).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}
