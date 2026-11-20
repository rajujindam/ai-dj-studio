package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.acoustic.AcousticMatchResult;
import com.aidjstudio.ai_dj_studio.dto.acoustic.CandidateRankingContext;
import com.aidjstudio.ai_dj_studio.dto.acoustic.HarmonicRelation;
import com.aidjstudio.ai_dj_studio.dto.acoustic.RankedCandidate;
import com.aidjstudio.ai_dj_studio.dto.acoustic.RankingScoreBreakdown;
import com.aidjstudio.ai_dj_studio.dto.sentiment.RoomSentimentSnapshot;
import com.aidjstudio.ai_dj_studio.entity.Track;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CandidateRankingService {

    public static final double HARMONIC_WEIGHT = 0.50;
    public static final double ENERGY_WEIGHT = 0.30;
    public static final double RECENCY_WEIGHT = 0.20;

    public static final double HARMONIC_SAME_KEY_SCORE = 1.0;
    public static final double HARMONIC_ADJACENT_KEY_SCORE = 0.8;
    public static final double HARMONIC_RELATIVE_KEY_SCORE = 0.7;

    /**
     * Maximum energy shift caused by crowd sentiment.
     * Range: -0.30 (full negative / fatigued) to +0.30 (full positive / energetic).
     */
    public static final double MAX_SENTIMENT_ENERGY_SHIFT = 0.30;

    private static final double SCORE_EPSILON = 1e-6;

    private final AcousticMatcherService acousticMatcherService;

    private record TargetEnergyPlan(
            BigDecimal effectiveTargetEnergy,
            BigDecimal baseEnergy,
            Double effectiveSentimentScore,
            BigDecimal sentimentEnergyShift
    ) {}

    /**
     * Ranks compatible candidates based on:
     * - 50% Harmonic Closeness
     * - 30% Energy-curve fit against sentiment / target energy
     * - 20% Recency (deprioritizing recently played tracks)
     *
     * Incompatible candidates (rejected by F10) are filtered out and never enter ranking.
     */
    public List<RankedCandidate> rankCandidates(
            Track currentTrack,
            List<Track> candidates,
            List<Long> recentTrackIds,
            BigDecimal targetEnergy,
            Double bpmTolerancePct,
            RoomSentimentSnapshot roomSentiment,
            Double sentimentScore
    ) {
        if (currentTrack == null) {
            throw new IllegalArgumentException("Current track cannot be null");
        }
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        double tolerance = bpmTolerancePct != null ? bpmTolerancePct : AcousticMatcherService.DEFAULT_BPM_TOLERANCE_PCT;
        TargetEnergyPlan energyPlan = resolveTargetEnergy(currentTrack, targetEnergy, roomSentiment, sentimentScore);

        List<RankedCandidate> scoredList = new ArrayList<>();

        for (Track candidate : candidates) {
            if (candidate == null) {
                continue;
            }

            // F10 Hard Constraint Check: Segue Gatekeeper
            // Acoustic compatibility must NEVER be overridden by sentiment or preference
            AcousticMatchResult matchResult = acousticMatcherService.evaluateMatch(currentTrack, candidate, tolerance);
            if (!matchResult.isCompatible()) {
                // Incompatible candidate never enters ranking
                continue;
            }

            RankingScoreBreakdown breakdown = computeScoreBreakdown(
                    candidate,
                    matchResult,
                    energyPlan,
                    recentTrackIds
            );

            scoredList.add(RankedCandidate.builder()
                    .track(candidate)
                    .matchResult(matchResult)
                    .scoreBreakdown(breakdown)
                    .build());
        }

        // Deterministic Tie-Breaking Sort
        scoredList.sort(createDeterministicComparator());

        // Assign 1-based ranks
        for (int i = 0; i < scoredList.size(); i++) {
            scoredList.get(i).setRank(i + 1);
        }

        return scoredList;
    }

    public List<RankedCandidate> rankCandidates(
            Track currentTrack,
            List<Track> candidates,
            List<Long> recentTrackIds,
            BigDecimal targetEnergy,
            Double bpmTolerancePct
    ) {
        return rankCandidates(
                currentTrack,
                candidates,
                recentTrackIds,
                targetEnergy,
                bpmTolerancePct,
                null,
                null
        );
    }

    public List<RankedCandidate> rankCandidates(
            Track currentTrack,
            List<Track> candidates,
            List<Long> recentTrackIds,
            BigDecimal targetEnergy
    ) {
        return rankCandidates(
                currentTrack,
                candidates,
                recentTrackIds,
                targetEnergy,
                AcousticMatcherService.DEFAULT_BPM_TOLERANCE_PCT,
                null,
                null
        );
    }

    public List<RankedCandidate> rankCandidates(
            Track currentTrack,
            List<Track> candidates,
            List<Long> recentTrackIds,
            RoomSentimentSnapshot roomSentiment
    ) {
        return rankCandidates(
                currentTrack,
                candidates,
                recentTrackIds,
                (BigDecimal) null,
                AcousticMatcherService.DEFAULT_BPM_TOLERANCE_PCT,
                roomSentiment,
                null
        );
    }

    public List<RankedCandidate> rankCandidates(
            Track currentTrack,
            List<Track> candidates,
            RoomSentimentSnapshot roomSentiment
    ) {
        return rankCandidates(
                currentTrack,
                candidates,
                Collections.emptyList(),
                (BigDecimal) null,
                AcousticMatcherService.DEFAULT_BPM_TOLERANCE_PCT,
                roomSentiment,
                null
        );
    }

    public List<RankedCandidate> rankCandidates(Track currentTrack, List<Track> candidates) {
        return rankCandidates(
                currentTrack,
                candidates,
                Collections.emptyList(),
                (BigDecimal) null,
                AcousticMatcherService.DEFAULT_BPM_TOLERANCE_PCT,
                null,
                null
        );
    }

    public List<RankedCandidate> rankCandidates(CandidateRankingContext context) {
        if (context == null) {
            throw new IllegalArgumentException("CandidateRankingContext cannot be null");
        }
        return rankCandidates(
                context.getCurrentTrack(),
                context.getCandidates(),
                context.getRecentTrackIds(),
                context.getTargetEnergy(),
                context.getBpmTolerancePct(),
                context.getRoomSentiment(),
                context.getSentimentScore()
        );
    }

    public Mono<List<RankedCandidate>> rankCandidatesReactive(CandidateRankingContext context) {
        return Mono.fromCallable(() -> rankCandidates(context));
    }

    public Flux<RankedCandidate> rankCandidatesFlux(CandidateRankingContext context) {
        return Mono.fromCallable(() -> rankCandidates(context))
                .flatMapMany(Flux::fromIterable);
    }

    private RankingScoreBreakdown computeScoreBreakdown(
            Track candidate,
            AcousticMatchResult matchResult,
            TargetEnergyPlan energyPlan,
            List<Long> recentTrackIds
    ) {
        // 1. Harmonic Closeness (50%)
        double harmonicScore = computeHarmonicScore(matchResult.getHarmonicRelation());
        double harmonicWeighted = harmonicScore * HARMONIC_WEIGHT;

        // 2. Energy Fit (30%) - Influenced by Rolling Sentiment in F13
        BigDecimal candidateEnergy = candidate.getEnergy() != null
                ? clampEnergy(candidate.getEnergy())
                : BigDecimal.valueOf(0.5);
        double energyDistance = Math.abs(candidateEnergy.doubleValue() - energyPlan.effectiveTargetEnergy().doubleValue());
        double energyScore = Math.max(0.0, Math.min(1.0, 1.0 - energyDistance));
        double energyWeighted = energyScore * ENERGY_WEIGHT;

        // 3. Recency Score (20%)
        Integer recentIndex = findRecentIndex(candidate.getId(), recentTrackIds);
        double recencyScore = computeRecencyScore(recentIndex, recentTrackIds);
        double recencyWeighted = recencyScore * RECENCY_WEIGHT;

        // Total Score
        double rawTotal = harmonicWeighted + energyWeighted + recencyWeighted;
        double roundedTotal = BigDecimal.valueOf(rawTotal)
                .setScale(4, RoundingMode.HALF_UP)
                .doubleValue();

        String reason = buildScoreExplanation(
                roundedTotal,
                harmonicScore,
                harmonicWeighted,
                matchResult.getHarmonicRelation(),
                energyScore,
                energyWeighted,
                candidateEnergy,
                energyPlan,
                recencyScore,
                recencyWeighted,
                recentIndex,
                recentTrackIds
        );

        return RankingScoreBreakdown.builder()
                .totalScore(roundedTotal)
                .harmonicScore(harmonicScore)
                .harmonicWeight(HARMONIC_WEIGHT)
                .harmonicWeightedScore(roundTo4(harmonicWeighted))
                .energyScore(roundTo4(energyScore))
                .energyWeight(ENERGY_WEIGHT)
                .energyWeightedScore(roundTo4(energyWeighted))
                .candidateEnergy(candidateEnergy)
                .targetEnergy(energyPlan.effectiveTargetEnergy())
                .baseEnergy(energyPlan.baseEnergy())
                .sentimentScore(energyPlan.effectiveSentimentScore())
                .sentimentEnergyShift(energyPlan.sentimentEnergyShift())
                .recencyScore(roundTo4(recencyScore))
                .recencyWeight(RECENCY_WEIGHT)
                .recencyWeightedScore(roundTo4(recencyWeighted))
                .recentIndex(recentIndex)
                .reason(reason)
                .build();
    }

    private double computeHarmonicScore(HarmonicRelation relation) {
        if (relation == null) {
            return 0.0;
        }
        return switch (relation) {
            case SAME_KEY -> HARMONIC_SAME_KEY_SCORE;
            case ADJACENT_KEY -> HARMONIC_ADJACENT_KEY_SCORE;
            case RELATIVE_KEY -> HARMONIC_RELATIVE_KEY_SCORE;
            case CLASH -> 0.0;
        };
    }

    private Integer findRecentIndex(Long trackId, List<Long> recentTrackIds) {
        if (trackId == null || recentTrackIds == null || recentTrackIds.isEmpty()) {
            return null;
        }
        int idx = recentTrackIds.indexOf(trackId);
        return idx >= 0 ? idx : null;
    }

    private double computeRecencyScore(Integer recentIndex, List<Long> recentTrackIds) {
        if (recentIndex == null || recentTrackIds == null || recentTrackIds.isEmpty()) {
            return 1.0; // Not recently played -> full score, no penalty
        }
        int windowSize = recentTrackIds.size();
        if (windowSize <= 1) {
            return 0.0; // Only 1 track in history and candidate was it -> maximum penalty
        }
        // Linear decay: index 0 (played 1 track ago) -> 0.0
        // index windowSize-1 (played furthest back in window) -> (windowSize-1)/windowSize
        return (double) recentIndex / (double) windowSize;
    }

    private Comparator<RankedCandidate> createDeterministicComparator() {
        return (c1, c2) -> {
            // 1. Primary: Total Score descending
            double score1 = c1.getScoreBreakdown().getTotalScore();
            double score2 = c2.getScoreBreakdown().getTotalScore();
            if (Math.abs(score1 - score2) > SCORE_EPSILON) {
                return Double.compare(score2, score1);
            }

            // 2. Secondary: Absolute BPM Delta % ascending (closer tempo is better)
            BigDecimal delta1 = c1.getMatchResult().getBpmDeltaPercent() != null
                    ? c1.getMatchResult().getBpmDeltaPercent().abs()
                    : BigDecimal.valueOf(Double.MAX_VALUE);
            BigDecimal delta2 = c2.getMatchResult().getBpmDeltaPercent() != null
                    ? c2.getMatchResult().getBpmDeltaPercent().abs()
                    : BigDecimal.valueOf(Double.MAX_VALUE);
            int bpmComparison = delta1.compareTo(delta2);
            if (bpmComparison != 0) {
                return bpmComparison;
            }

            // 3. Tertiary: Track ID ascending (deterministic tie-breaker)
            Long id1 = c1.getTrack() != null ? c1.getTrack().getId() : null;
            Long id2 = c2.getTrack() != null ? c2.getTrack().getId() : null;
            if (id1 != null && id2 != null) {
                return Long.compare(id1, id2);
            } else if (id1 != null) {
                return -1;
            } else if (id2 != null) {
                return 1;
            }

            return 0;
        };
    }

    /**
     * Resolves target energy incorporating crowd rolling sentiment (F13):
     * Target Energy = clamp(baseEnergy + (sentimentScore * 0.30), 0.0, 1.0)
     */
    private TargetEnergyPlan resolveTargetEnergy(
            Track currentTrack,
            BigDecimal explicitTargetEnergy,
            RoomSentimentSnapshot roomSentiment,
            Double explicitSentimentScore
    ) {
        BigDecimal base = explicitTargetEnergy != null
                ? clampEnergy(explicitTargetEnergy)
                : (currentTrack.getEnergy() != null ? clampEnergy(currentTrack.getEnergy()) : BigDecimal.valueOf(0.5));

        Double score = explicitSentimentScore;
        if (score == null && roomSentiment != null) {
            score = roomSentiment.getRoomScore();
        }

        if (score != null) {
            // Clamp sentiment score to [-1.0, +1.0]
            double clampedScore = Math.max(-1.0, Math.min(1.0, score));
            double shiftValue = clampedScore * MAX_SENTIMENT_ENERGY_SHIFT;
            BigDecimal shift = BigDecimal.valueOf(shiftValue).setScale(2, RoundingMode.HALF_UP);
            BigDecimal target = clampEnergy(base.add(shift));

            return new TargetEnergyPlan(target, base, clampedScore, shift);
        }

        return new TargetEnergyPlan(base, base, null, BigDecimal.ZERO);
    }

    private BigDecimal clampEnergy(BigDecimal energy) {
        if (energy.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (energy.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return energy.setScale(2, RoundingMode.HALF_UP);
    }

    private double roundTo4(double val) {
        return BigDecimal.valueOf(val).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private String buildScoreExplanation(
            double totalScore,
            double harmonicScore,
            double harmonicWeighted,
            HarmonicRelation relation,
            double energyScore,
            double energyWeighted,
            BigDecimal candidateEnergy,
            TargetEnergyPlan energyPlan,
            double recencyScore,
            double recencyWeighted,
            Integer recentIndex,
            List<Long> recentTrackIds
    ) {
        String recencyDetail = recentIndex == null
                ? "not in recent window"
                : String.format("played %d tracks ago (index %d/%d)", recentIndex + 1, recentIndex, recentTrackIds.size());

        String energyDetail;
        if (energyPlan.effectiveSentimentScore() != null) {
            energyDetail = String.format(
                    "cand=%.2f, target=%.2f [base=%.2f, shift=%+.2f from sentiment %.2f]",
                    candidateEnergy,
                    energyPlan.effectiveTargetEnergy(),
                    energyPlan.baseEnergy(),
                    energyPlan.sentimentEnergyShift(),
                    energyPlan.effectiveSentimentScore()
            );
        } else {
            energyDetail = String.format("cand=%.2f, target=%.2f", candidateEnergy, energyPlan.effectiveTargetEnergy());
        }

        return String.format(
                "Rank Score: %.4f [Harmonic(50%%): %.2f * 0.50 = %.4f (%s) | Energy(30%%): %.2f * 0.30 = %.4f (%s) | Recency(20%%): %.2f * 0.20 = %.4f (%s)]",
                totalScore,
                harmonicScore,
                harmonicWeighted,
                relation,
                energyScore,
                energyWeighted,
                energyDetail,
                recencyScore,
                recencyWeighted,
                recencyDetail
        );
    }
}
