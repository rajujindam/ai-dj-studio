package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.acoustic.CandidateRankingContext;
import com.aidjstudio.ai_dj_studio.dto.acoustic.HarmonicRelation;
import com.aidjstudio.ai_dj_studio.dto.acoustic.RankedCandidate;
import com.aidjstudio.ai_dj_studio.dto.acoustic.RankingScoreBreakdown;
import com.aidjstudio.ai_dj_studio.entity.Track;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateRankingServiceTest {

    private AcousticMatcherService acousticMatcherService;
    private CandidateRankingService rankingService;

    @BeforeEach
    void setUp() {
        acousticMatcherService = new AcousticMatcherService();
        rankingService = new CandidateRankingService(acousticMatcherService);
    }

    private Track createTrack(Long id, String title, String artist, String bpm, String key, String energy) {
        Track t = new Track();
        t.setId(id);
        t.setTitle(title);
        t.setArtist(artist);
        t.setDurationSeconds(180);
        t.setBpm(new BigDecimal(bpm));
        t.setCamelotKey(key);
        t.setEnergy(new BigDecimal(energy));
        t.setValence(new BigDecimal("0.50"));
        return t;
    }

    // ==========================================
    // 1. GATEKEEPER TESTS: INCOMPATIBLE TRACKS
    // ==========================================

    @Nested
    @DisplayName("F10 Gatekeeper Hard Constraint Tests")
    class GatekeeperTests {

        @Test
        @DisplayName("Incompatible candidates never enter ranking")
        void incompatibleCandidates_neverEnterRanking() {
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.80");

            Track compatible1 = createTrack(2L, "Comp1", "Artist", "122.00", "8A", "0.80"); // SAME_KEY, within 8%
            Track bpmClash = createTrack(3L, "BpmClash", "Artist", "150.00", "8A", "0.80"); // 150 BPM > 8% of 120
            Track keyClash = createTrack(4L, "KeyClash", "Artist", "120.00", "11B", "0.80"); // 11B clashes with 8A
            Track bothClash = createTrack(5L, "BothClash", "Artist", "180.00", "2B", "0.80");

            List<RankedCandidate> result = rankingService.rankCandidates(
                    current,
                    Arrays.asList(compatible1, bpmClash, keyClash, bothClash)
            );

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTrack().getId()).isEqualTo(2L);
            assertThat(result.get(0).getRank()).isEqualTo(1);
            assertThat(result.get(0).getMatchResult().isCompatible()).isTrue();
        }

        @Test
        @DisplayName("When all candidates are incompatible, returns empty list")
        void allIncompatible_returnsEmpty() {
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.80");
            Track bpmClash = createTrack(2L, "BpmClash", "Artist", "160.00", "8A", "0.80");
            Track keyClash = createTrack(3L, "KeyClash", "Artist", "120.00", "10B", "0.80");

            List<RankedCandidate> result = rankingService.rankCandidates(current, Arrays.asList(bpmClash, keyClash));
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Null or empty candidates return empty list")
        void emptyOrNullCandidates_returnsEmpty() {
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.80");

            assertThat(rankingService.rankCandidates(current, Collections.emptyList())).isEmpty();
            assertThat(rankingService.rankCandidates(current, null)).isEmpty();
        }

        @Test
        @DisplayName("Null current track throws IllegalArgumentException")
        void nullCurrentTrack_throwsException() {
            Track cand = createTrack(2L, "Cand", "Artist", "120.00", "8A", "0.80");

            assertThatThrownBy(() -> rankingService.rankCandidates(null, List.of(cand)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Current track cannot be null");
        }
    }

    // ==========================================
    // 2. WEIGHT CONTRIBUTION TESTS (50/30/20)
    // ==========================================

    @Nested
    @DisplayName("Weight Contribution & Sub-score Tests")
    class WeightContributionTests {

        @Test
        @DisplayName("Harmonic Closeness 50% contribution: Same (1.0) vs Adjacent (0.8) vs Relative (0.7)")
        void harmonicWeights_contribution() {
            // All candidates have identical BPM, identical energy (1.0 - 0 = 1.0 fit), and none are recent (recency = 1.0)
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.80");

            Track sameKey = createTrack(10L, "SameKey", "Artist", "120.00", "8A", "0.80");
            Track adjacentKey = createTrack(11L, "AdjacentKey", "Artist", "120.00", "9A", "0.80");
            Track relativeKey = createTrack(12L, "RelativeKey", "Artist", "120.00", "8B", "0.80");

            List<RankedCandidate> ranked = rankingService.rankCandidates(
                    current,
                    Arrays.asList(relativeKey, adjacentKey, sameKey)
            );

            assertThat(ranked).hasSize(3);

            // 1st: Same key (1.0 * 0.50 + 1.0 * 0.30 + 1.0 * 0.20 = 1.0000)
            RankedCandidate r1 = ranked.get(0);
            assertThat(r1.getRank()).isEqualTo(1);
            assertThat(r1.getTrack().getId()).isEqualTo(10L);
            assertThat(r1.getMatchResult().getHarmonicRelation()).isEqualTo(HarmonicRelation.SAME_KEY);
            RankingScoreBreakdown b1 = r1.getScoreBreakdown();
            assertThat(b1.getHarmonicScore()).isEqualTo(1.0);
            assertThat(b1.getHarmonicWeightedScore()).isEqualTo(0.5000);
            assertThat(b1.getEnergyScore()).isEqualTo(1.0);
            assertThat(b1.getEnergyWeightedScore()).isEqualTo(0.3000);
            assertThat(b1.getRecencyScore()).isEqualTo(1.0);
            assertThat(b1.getRecencyWeightedScore()).isEqualTo(0.2000);
            assertThat(b1.getTotalScore()).isEqualTo(1.0000);

            // 2nd: Adjacent key (0.8 * 0.50 + 1.0 * 0.30 + 1.0 * 0.20 = 0.40 + 0.30 + 0.20 = 0.9000)
            RankedCandidate r2 = ranked.get(1);
            assertThat(r2.getRank()).isEqualTo(2);
            assertThat(r2.getTrack().getId()).isEqualTo(11L);
            assertThat(r2.getMatchResult().getHarmonicRelation()).isEqualTo(HarmonicRelation.ADJACENT_KEY);
            RankingScoreBreakdown b2 = r2.getScoreBreakdown();
            assertThat(b2.getHarmonicScore()).isEqualTo(0.8);
            assertThat(b2.getHarmonicWeightedScore()).isEqualTo(0.4000);
            assertThat(b2.getTotalScore()).isEqualTo(0.9000);

            // 3rd: Relative key (0.7 * 0.50 + 1.0 * 0.30 + 1.0 * 0.20 = 0.35 + 0.30 + 0.20 = 0.8500)
            RankedCandidate r3 = ranked.get(2);
            assertThat(r3.getRank()).isEqualTo(3);
            assertThat(r3.getTrack().getId()).isEqualTo(12L);
            assertThat(r3.getMatchResult().getHarmonicRelation()).isEqualTo(HarmonicRelation.RELATIVE_KEY);
            RankingScoreBreakdown b3 = r3.getScoreBreakdown();
            assertThat(b3.getHarmonicScore()).isEqualTo(0.7);
            assertThat(b3.getHarmonicWeightedScore()).isEqualTo(0.3500);
            assertThat(b3.getTotalScore()).isEqualTo(0.8500);
        }

        @Test
        @DisplayName("Energy curve fit 30% contribution: closer energy to target ranks higher")
        void energyCurveFit_contribution() {
            // Current track 8A, 120 BPM, target energy = 0.90
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.50");

            // Candidate A: 8A, energy 0.90 (diff = 0.0 -> score 1.0, weighted = 0.30)
            Track candA = createTrack(20L, "PerfectEnergy", "Artist", "120.00", "8A", "0.90");
            // Candidate B: 8A, energy 0.70 (diff = 0.2 -> score 0.8, weighted = 0.24)
            Track candB = createTrack(21L, "MediumEnergy", "Artist", "120.00", "8A", "0.70");
            // Candidate C: 8A, energy 0.20 (diff = 0.7 -> score 0.3, weighted = 0.09)
            Track candC = createTrack(22L, "LowEnergy", "Artist", "120.00", "8A", "0.20");

            BigDecimal targetEnergy = new BigDecimal("0.90");
            List<RankedCandidate> ranked = rankingService.rankCandidates(
                    current,
                    Arrays.asList(candC, candA, candB),
                    Collections.emptyList(),
                    targetEnergy
            );

            assertThat(ranked).hasSize(3);
            assertThat(ranked.get(0).getTrack().getId()).isEqualTo(20L); // Perfect energy
            assertThat(ranked.get(0).getScoreBreakdown().getEnergyScore()).isEqualTo(1.0);
            assertThat(ranked.get(0).getScoreBreakdown().getTotalScore()).isEqualTo(1.0000);

            assertThat(ranked.get(1).getTrack().getId()).isEqualTo(21L); // Medium energy
            assertThat(ranked.get(1).getScoreBreakdown().getEnergyScore()).isEqualTo(0.8);
            assertThat(ranked.get(1).getScoreBreakdown().getTotalScore()).isEqualTo(0.9400); // 0.50 + 0.24 + 0.20

            assertThat(ranked.get(2).getTrack().getId()).isEqualTo(22L); // Low energy
            assertThat(ranked.get(2).getScoreBreakdown().getEnergyScore()).isEqualTo(0.3);
            assertThat(ranked.get(2).getScoreBreakdown().getTotalScore()).isEqualTo(0.7900); // 0.50 + 0.09 + 0.20
        }
    }

    // ==========================================
    // 3. RECENCY PENALTY TESTS (20%)
    // ==========================================

    @Nested
    @DisplayName("Recency Penalty Tests")
    class RecencyPenaltyTests {

        @Test
        @DisplayName("Recently played tracks receive penalty based on linear decay in window")
        void recencyPenalty_linearDecay() {
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.80");

            // 4 tracks with identical harmonic and energy profiles
            Track trackJustPlayed = createTrack(100L, "JustPlayed", "Artist", "120.00", "8A", "0.80");
            Track trackPlayedMiddle = createTrack(101L, "PlayedMiddle", "Artist", "120.00", "8A", "0.80");
            Track trackPlayedOld = createTrack(102L, "PlayedOld", "Artist", "120.00", "8A", "0.80");
            Track trackNotRecent = createTrack(103L, "NotRecent", "Artist", "120.00", "8A", "0.80");

            // Recent window of size 5: [100 (most recent, idx 0), 200, 101 (idx 2), 300, 102 (idx 4)]
            List<Long> recentTrackIds = Arrays.asList(100L, 200L, 101L, 300L, 102L);

            List<RankedCandidate> ranked = rankingService.rankCandidates(
                    current,
                    Arrays.asList(trackJustPlayed, trackPlayedOld, trackNotRecent, trackPlayedMiddle),
                    recentTrackIds,
                    new BigDecimal("0.80")
            );

            assertThat(ranked).hasSize(4);

            // 1st: NotRecent (not in recent window -> recencyScore = 1.0, weighted = 0.20)
            assertThat(ranked.get(0).getTrack().getId()).isEqualTo(103L);
            assertThat(ranked.get(0).getScoreBreakdown().getRecencyScore()).isEqualTo(1.0);
            assertThat(ranked.get(0).getScoreBreakdown().getTotalScore()).isEqualTo(1.0000);

            // 2nd: PlayedOld (index 4 in window of 5 -> 4/5 = 0.8, weighted = 0.16)
            assertThat(ranked.get(1).getTrack().getId()).isEqualTo(102L);
            assertThat(ranked.get(1).getScoreBreakdown().getRecencyScore()).isEqualTo(0.8);
            assertThat(ranked.get(1).getScoreBreakdown().getTotalScore()).isEqualTo(0.9600); // 0.50 + 0.30 + 0.16

            // 3rd: PlayedMiddle (index 2 in window of 5 -> 2/5 = 0.4, weighted = 0.08)
            assertThat(ranked.get(2).getTrack().getId()).isEqualTo(101L);
            assertThat(ranked.get(2).getScoreBreakdown().getRecencyScore()).isEqualTo(0.4);
            assertThat(ranked.get(2).getScoreBreakdown().getTotalScore()).isEqualTo(0.8800); // 0.50 + 0.30 + 0.08

            // 4th: JustPlayed (index 0 in window of 5 -> 0/5 = 0.0, weighted = 0.00)
            assertThat(ranked.get(3).getTrack().getId()).isEqualTo(100L);
            assertThat(ranked.get(3).getScoreBreakdown().getRecencyScore()).isEqualTo(0.0);
            assertThat(ranked.get(3).getScoreBreakdown().getTotalScore()).isEqualTo(0.8000); // 0.50 + 0.30 + 0.00
        }

        @Test
        @DisplayName("Single recent track in history gives candidate 0.0 recency score")
        void singleRecentTrack_givesZero() {
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.80");
            Track candidate = createTrack(50L, "Candidate", "Artist", "120.00", "8A", "0.80");

            List<RankedCandidate> ranked = rankingService.rankCandidates(
                    current,
                    List.of(candidate),
                    List.of(50L),
                    new BigDecimal("0.80")
            );

            assertThat(ranked).hasSize(1);
            assertThat(ranked.get(0).getScoreBreakdown().getRecencyScore()).isEqualTo(0.0);
            assertThat(ranked.get(0).getScoreBreakdown().getTotalScore()).isEqualTo(0.8000);
        }
    }

    // ==========================================
    // 4. DETERMINISTIC TIE-BREAKER TESTS
    // ==========================================

    @Nested
    @DisplayName("Deterministic Tie-Breaker Tests")
    class TieBreakerTests {

        @Test
        @DisplayName("Tie in total score is broken by lower BPM delta percentage")
        void tie_brokenByBpmDeltaPercent() {
            // Current is 120 BPM, 8A, energy 0.80
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.80");

            // Candidate A: 122 BPM (delta % = +1.67%)
            Track candA = createTrack(200L, "CandA_CloseBpm", "Artist", "122.00", "8A", "0.80");
            // Candidate B: 126 BPM (delta % = +5.00%)
            Track candB = createTrack(100L, "CandB_FarBpm", "Artist", "126.00", "8A", "0.80");

            // Both have SameKey (0.50), same energy (0.30), neither is recent (0.20) -> total score 1.0000
            List<RankedCandidate> ranked = rankingService.rankCandidates(
                    current,
                    Arrays.asList(candB, candA) // Pass B first
            );

            assertThat(ranked).hasSize(2);
            // candA wins because |1.67%| < |5.00%|, even though candB has a lower track ID (100 vs 200)
            assertThat(ranked.get(0).getTrack().getId()).isEqualTo(200L);
            assertThat(ranked.get(0).getRank()).isEqualTo(1);
            assertThat(ranked.get(1).getTrack().getId()).isEqualTo(100L);
            assertThat(ranked.get(1).getRank()).isEqualTo(2);
        }

        @Test
        @DisplayName("Tie in total score and BPM delta is broken by lower Track ID")
        void tie_brokenByTrackId() {
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.80");

            // Candidate A: ID 50, 120.00 BPM
            Track candA = createTrack(50L, "CandA", "Artist", "120.00", "8A", "0.80");
            // Candidate B: ID 15, 120.00 BPM
            Track candB = createTrack(15L, "CandB", "Artist", "120.00", "8A", "0.80");

            // Both have exact same score (1.0000) and BPM delta (0.00%)
            List<RankedCandidate> ranked = rankingService.rankCandidates(
                    current,
                    Arrays.asList(candA, candB)
            );

            assertThat(ranked).hasSize(2);
            // Lower track ID wins deterministically: 15L < 50L
            assertThat(ranked.get(0).getTrack().getId()).isEqualTo(15L);
            assertThat(ranked.get(0).getRank()).isEqualTo(1);
            assertThat(ranked.get(1).getTrack().getId()).isEqualTo(50L);
            assertThat(ranked.get(1).getRank()).isEqualTo(2);
        }
    }

    // ==========================================
    // 5. CONTEXT & REACTIVE EXECUTION TESTS
    // ==========================================

    @Nested
    @DisplayName("Context & Reactive Execution Tests")
    class ReactiveAndContextTests {

        @Test
        @DisplayName("rankCandidates with CandidateRankingContext works cleanly")
        void contextObject_ranking() {
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.80");
            Track cand = createTrack(2L, "Cand", "Artist", "120.00", "8A", "0.80");

            CandidateRankingContext context = CandidateRankingContext.builder()
                    .currentTrack(current)
                    .candidates(List.of(cand))
                    .targetEnergy(new BigDecimal("0.80"))
                    .build();

            List<RankedCandidate> ranked = rankingService.rankCandidates(context);
            assertThat(ranked).hasSize(1);
            assertThat(ranked.get(0).getRank()).isEqualTo(1);
            assertThat(ranked.get(0).getScoreBreakdown().getReason()).contains("Rank Score: 1.0000");
        }

        @Test
        @DisplayName("Reactive rankCandidatesReactive returns Mono with ranked candidates")
        void reactiveMono_ranking() {
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.80");
            Track cand = createTrack(2L, "Cand", "Artist", "120.00", "8A", "0.80");

            CandidateRankingContext context = CandidateRankingContext.builder()
                    .currentTrack(current)
                    .candidates(List.of(cand))
                    .build();

            StepVerifier.create(rankingService.rankCandidatesReactive(context))
                    .assertNext(list -> {
                        assertThat(list).hasSize(1);
                        assertThat(list.get(0).getRank()).isEqualTo(1);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("Reactive rankCandidatesFlux emits RankedCandidate stream")
        void reactiveFlux_ranking() {
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.80");
            Track cand1 = createTrack(2L, "Cand1", "Artist", "120.00", "8A", "0.80");
            Track cand2 = createTrack(3L, "Cand2", "Artist", "120.00", "9A", "0.80");

            CandidateRankingContext context = CandidateRankingContext.builder()
                    .currentTrack(current)
                    .candidates(List.of(cand1, cand2))
                    .build();

            StepVerifier.create(rankingService.rankCandidatesFlux(context))
                    .assertNext(rc -> assertThat(rc.getRank()).isEqualTo(1))
                    .assertNext(rc -> assertThat(rc.getRank()).isEqualTo(2))
                    .verifyComplete();
        }
    }
}
