package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.acoustic.CandidateRankingContext;
import com.aidjstudio.ai_dj_studio.dto.acoustic.HarmonicRelation;
import com.aidjstudio.ai_dj_studio.dto.acoustic.RankedCandidate;
import com.aidjstudio.ai_dj_studio.dto.acoustic.RankingScoreBreakdown;
import com.aidjstudio.ai_dj_studio.dto.sentiment.RoomSentimentSnapshot;
import com.aidjstudio.ai_dj_studio.dto.sentiment.SentimentBucket;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.service.sentiment.LexiconSentimentScorer;
import com.aidjstudio.ai_dj_studio.service.sentiment.RollingWindowSentimentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
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
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.50");

            Track candA = createTrack(20L, "PerfectEnergy", "Artist", "120.00", "8A", "0.90");
            Track candB = createTrack(21L, "MediumEnergy", "Artist", "120.00", "8A", "0.70");
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
            assertThat(ranked.get(1).getScoreBreakdown().getTotalScore()).isEqualTo(0.9400);

            assertThat(ranked.get(2).getTrack().getId()).isEqualTo(22L); // Low energy
            assertThat(ranked.get(2).getScoreBreakdown().getEnergyScore()).isEqualTo(0.3);
            assertThat(ranked.get(2).getScoreBreakdown().getTotalScore()).isEqualTo(0.7900);
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

            Track trackJustPlayed = createTrack(100L, "JustPlayed", "Artist", "120.00", "8A", "0.80");
            Track trackPlayedMiddle = createTrack(101L, "PlayedMiddle", "Artist", "120.00", "8A", "0.80");
            Track trackPlayedOld = createTrack(102L, "PlayedOld", "Artist", "120.00", "8A", "0.80");
            Track trackNotRecent = createTrack(103L, "NotRecent", "Artist", "120.00", "8A", "0.80");

            List<Long> recentTrackIds = Arrays.asList(100L, 200L, 101L, 300L, 102L);

            List<RankedCandidate> ranked = rankingService.rankCandidates(
                    current,
                    Arrays.asList(trackJustPlayed, trackPlayedOld, trackNotRecent, trackPlayedMiddle),
                    recentTrackIds,
                    new BigDecimal("0.80")
            );

            assertThat(ranked).hasSize(4);

            // 1st: NotRecent (recencyScore = 1.0, weighted = 0.20)
            assertThat(ranked.get(0).getTrack().getId()).isEqualTo(103L);
            assertThat(ranked.get(0).getScoreBreakdown().getRecencyScore()).isEqualTo(1.0);
            assertThat(ranked.get(0).getScoreBreakdown().getTotalScore()).isEqualTo(1.0000);

            // 2nd: PlayedOld (4/5 = 0.8, weighted = 0.16)
            assertThat(ranked.get(1).getTrack().getId()).isEqualTo(102L);
            assertThat(ranked.get(1).getScoreBreakdown().getRecencyScore()).isEqualTo(0.8);
            assertThat(ranked.get(1).getScoreBreakdown().getTotalScore()).isEqualTo(0.9600);

            // 3rd: PlayedMiddle (2/5 = 0.4, weighted = 0.08)
            assertThat(ranked.get(2).getTrack().getId()).isEqualTo(101L);
            assertThat(ranked.get(2).getScoreBreakdown().getRecencyScore()).isEqualTo(0.4);
            assertThat(ranked.get(2).getScoreBreakdown().getTotalScore()).isEqualTo(0.8800);

            // 4th: JustPlayed (0/5 = 0.0, weighted = 0.00)
            assertThat(ranked.get(3).getTrack().getId()).isEqualTo(100L);
            assertThat(ranked.get(3).getScoreBreakdown().getRecencyScore()).isEqualTo(0.0);
            assertThat(ranked.get(3).getScoreBreakdown().getTotalScore()).isEqualTo(0.8000);
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
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.80");

            Track candA = createTrack(200L, "CandA_CloseBpm", "Artist", "122.00", "8A", "0.80");
            Track candB = createTrack(100L, "CandB_FarBpm", "Artist", "126.00", "8A", "0.80");

            List<RankedCandidate> ranked = rankingService.rankCandidates(
                    current,
                    Arrays.asList(candB, candA)
            );

            assertThat(ranked).hasSize(2);
            assertThat(ranked.get(0).getTrack().getId()).isEqualTo(200L);
            assertThat(ranked.get(0).getRank()).isEqualTo(1);
            assertThat(ranked.get(1).getTrack().getId()).isEqualTo(100L);
            assertThat(ranked.get(1).getRank()).isEqualTo(2);
        }

        @Test
        @DisplayName("Tie in total score and BPM delta is broken by lower Track ID")
        void tie_brokenByTrackId() {
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.80");

            Track candA = createTrack(50L, "CandA", "Artist", "120.00", "8A", "0.80");
            Track candB = createTrack(15L, "CandB", "Artist", "120.00", "8A", "0.80");

            List<RankedCandidate> ranked = rankingService.rankCandidates(
                    current,
                    Arrays.asList(candA, candB)
            );

            assertThat(ranked).hasSize(2);
            assertThat(ranked.get(0).getTrack().getId()).isEqualTo(15L);
            assertThat(ranked.get(0).getRank()).isEqualTo(1);
            assertThat(ranked.get(1).getTrack().getId()).isEqualTo(50L);
            assertThat(ranked.get(1).getRank()).isEqualTo(2);
        }
    }

    // ==========================================
    // 5. F13 SENTIMENT CURATION TESTS
    // ==========================================

    @Nested
    @DisplayName("F13 Sentiment Curation Tests")
    class SentimentCurationTests {

        @Test
        @DisplayName("Positive/energetic sentiment shifts target energy up (+0.30) and favors high-energy tracks")
        void positiveSentiment_shiftsEnergyUp_favorsHighEnergy() {
            // Base current track energy = 0.60
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.60");

            // Candidate High: energy 0.90
            Track candHigh = createTrack(10L, "HighEnergy", "Artist", "120.00", "8A", "0.90");
            // Candidate Low: energy 0.60
            Track candLow = createTrack(11L, "LowEnergy", "Artist", "120.00", "8A", "0.60");

            // Case A: Without sentiment, candLow (diff = 0) outranks candHigh (diff = 0.30)
            List<RankedCandidate> baseRanked = rankingService.rankCandidates(current, Arrays.asList(candHigh, candLow));
            assertThat(baseRanked.get(0).getTrack().getId()).isEqualTo(11L); // Low energy wins at base 0.60

            // Case B: With positive/energetic sentiment (+1.00), target energy shifts to 0.60 + 0.30 = 0.90!
            RoomSentimentSnapshot positiveSentiment = RoomSentimentSnapshot.builder()
                    .roomScore(1.000)
                    .normalizedVibe(1.000)
                    .dominantBucket(SentimentBucket.POSITIVE)
                    .totalSamples(5)
                    .build();

            CandidateRankingContext context = CandidateRankingContext.builder()
                    .currentTrack(current)
                    .candidates(Arrays.asList(candLow, candHigh))
                    .roomSentiment(positiveSentiment)
                    .build();

            List<RankedCandidate> sentimentRanked = rankingService.rankCandidates(context);

            assertThat(sentimentRanked).hasSize(2);
            // High energy candidate now wins because its 0.90 energy perfectly matches the +0.30 shifted target (0.90)
            assertThat(sentimentRanked.get(0).getTrack().getId()).isEqualTo(10L);
            assertThat(sentimentRanked.get(0).getRank()).isEqualTo(1);
            assertThat(sentimentRanked.get(0).getScoreBreakdown().getEnergyScore()).isEqualTo(1.0);
            assertThat(sentimentRanked.get(0).getScoreBreakdown().getTargetEnergy()).isEqualByComparingTo("0.90");
            assertThat(sentimentRanked.get(0).getScoreBreakdown().getBaseEnergy()).isEqualByComparingTo("0.60");
            assertThat(sentimentRanked.get(0).getScoreBreakdown().getSentimentScore()).isEqualTo(1.000);
            assertThat(sentimentRanked.get(0).getScoreBreakdown().getSentimentEnergyShift()).isEqualByComparingTo("0.30");

            // Low energy candidate (0.60) now has diff = |0.60 - 0.90| = 0.30, energy score = 0.70
            assertThat(sentimentRanked.get(1).getTrack().getId()).isEqualTo(11L);
            assertThat(sentimentRanked.get(1).getRank()).isEqualTo(2);
            assertThat(sentimentRanked.get(1).getScoreBreakdown().getEnergyScore()).isEqualTo(0.7);
        }

        @Test
        @DisplayName("Negative/fatigued sentiment shifts target energy down (-0.30) and favors chill/low-energy tracks")
        void negativeSentiment_shiftsEnergyDown_favorsChillLowEnergy() {
            // Base current track energy = 0.60
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.60");

            // Candidate High: energy 0.60
            Track candHigh = createTrack(20L, "HighEnergy", "Artist", "120.00", "8A", "0.60");
            // Candidate Chill: energy 0.30
            Track candChill = createTrack(21L, "ChillEnergy", "Artist", "120.00", "8A", "0.30");

            // Room sentiment is negative / crowd is fatigued (-1.00): target energy becomes 0.60 - 0.30 = 0.30!
            RoomSentimentSnapshot negativeSentiment = RoomSentimentSnapshot.builder()
                    .roomScore(-1.000)
                    .normalizedVibe(0.000)
                    .dominantBucket(SentimentBucket.NEGATIVE)
                    .totalSamples(4)
                    .build();

            CandidateRankingContext context = CandidateRankingContext.builder()
                    .currentTrack(current)
                    .candidates(Arrays.asList(candHigh, candChill))
                    .roomSentiment(negativeSentiment)
                    .build();

            List<RankedCandidate> ranked = rankingService.rankCandidates(context);

            assertThat(ranked).hasSize(2);
            // Chill energy candidate (0.30) wins because it matches the cooldown target (0.30)
            assertThat(ranked.get(0).getTrack().getId()).isEqualTo(21L);
            assertThat(ranked.get(0).getRank()).isEqualTo(1);
            assertThat(ranked.get(0).getScoreBreakdown().getEnergyScore()).isEqualTo(1.0);
            assertThat(ranked.get(0).getScoreBreakdown().getTargetEnergy()).isEqualByComparingTo("0.30");
            assertThat(ranked.get(0).getScoreBreakdown().getSentimentEnergyShift()).isEqualByComparingTo("-0.30");

            // High energy candidate has diff = |0.60 - 0.30| = 0.30, energy score = 0.70
            assertThat(ranked.get(1).getTrack().getId()).isEqualTo(20L);
            assertThat(ranked.get(1).getRank()).isEqualTo(2);
        }

        @Test
        @DisplayName("Neutral sentiment preserves current track's base energy with 0 shift")
        void neutralSentiment_preservesBaseEnergy() {
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.75");
            Track cand = createTrack(30L, "Cand", "Artist", "120.00", "8A", "0.75");

            RoomSentimentSnapshot neutralSentiment = RoomSentimentSnapshot.builder()
                    .roomScore(0.000)
                    .normalizedVibe(0.500)
                    .dominantBucket(SentimentBucket.NEUTRAL)
                    .totalSamples(2)
                    .build();

            CandidateRankingContext context = CandidateRankingContext.builder()
                    .currentTrack(current)
                    .candidates(List.of(cand))
                    .roomSentiment(neutralSentiment)
                    .build();

            List<RankedCandidate> ranked = rankingService.rankCandidates(context);

            assertThat(ranked).hasSize(1);
            assertThat(ranked.get(0).getScoreBreakdown().getTargetEnergy()).isEqualByComparingTo("0.75");
            assertThat(ranked.get(0).getScoreBreakdown().getSentimentEnergyShift()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("HARD RULE GUARANTEE: Incompatible candidate is NEVER ranked even with 100% sentiment match")
        void hardConstraintGuarantee_incompatibleTrackNeverRanked() {
            // Current is 120 BPM, 8A, energy 0.60
            Track current = createTrack(1L, "Current", "Artist", "120.00", "8A", "0.60");

            // Extreme hype sentiment (+1.00) -> Target energy = 0.90
            RoomSentimentSnapshot hypeSentiment = RoomSentimentSnapshot.builder()
                    .roomScore(1.000)
                    .dominantBucket(SentimentBucket.POSITIVE)
                    .build();

            // Candidate 1: Incompatible BPM (160 BPM) but PERFECT energy 0.90
            Track bpmClash = createTrack(100L, "BpmClash", "Artist", "160.00", "8A", "0.90");

            // Candidate 2: Incompatible Camelot key (11B vs 8A) but PERFECT energy 0.90
            Track keyClash = createTrack(101L, "KeyClash", "Artist", "120.00", "11B", "0.90");

            // Candidate 3: Compatible (120 BPM, 8A), but moderate energy (0.60 -> energy score 0.70)
            Track compatible = createTrack(102L, "Compatible", "Artist", "120.00", "8A", "0.60");

            CandidateRankingContext context = CandidateRankingContext.builder()
                    .currentTrack(current)
                    .candidates(Arrays.asList(bpmClash, keyClash, compatible))
                    .roomSentiment(hypeSentiment)
                    .build();

            List<RankedCandidate> ranked = rankingService.rankCandidates(context);

            // Both clash candidates are completely rejected by F10 gatekeeper
            assertThat(ranked).hasSize(1);
            assertThat(ranked.get(0).getTrack().getId()).isEqualTo(102L);
            assertThat(ranked.get(0).getMatchResult().isCompatible()).isTrue();
        }

        @Test
        @DisplayName("Full End-to-End Pipeline: RollingWindowSentimentService feeds CandidateRankingService")
        void endToEnd_sentimentServiceFeedsRanking() {
            RollingWindowSentimentService sentimentService = new RollingWindowSentimentService(new LexiconSentimentScorer());
            Long loungeId = 999L;
            Instant now = Instant.now();

            // Simulate crowd chatting enthusiastically
            sentimentService.recordMessage(loungeId, "Fire drop! Love this banger 🔥", 1L, now);
            sentimentService.recordMessage(loungeId, "Awesome vibes in the room!", 2L, now);

            RoomSentimentSnapshot snapshot = sentimentService.getRoomSentiment(loungeId);
            assertThat(snapshot.getRoomScore()).isGreaterThan(0.0);

            Track current = createTrack(1L, "Current", "Artist", "124.00", "8A", "0.50");
            Track highEnergyCand = createTrack(2L, "HighCand", "Artist", "124.00", "8A", "0.80");
            Track lowEnergyCand = createTrack(3L, "LowCand", "Artist", "124.00", "8A", "0.50");

            CandidateRankingContext context = CandidateRankingContext.builder()
                    .currentTrack(current)
                    .candidates(Arrays.asList(lowEnergyCand, highEnergyCand))
                    .roomSentiment(snapshot)
                    .build();

            List<RankedCandidate> ranked = rankingService.rankCandidates(context);

            assertThat(ranked).hasSize(2);
            // High energy candidate ranks #1 because live chat sentiment shifted target energy up!
            assertThat(ranked.get(0).getTrack().getId()).isEqualTo(2L);
            assertThat(ranked.get(0).getScoreBreakdown().getReason()).contains("from sentiment");
        }
    }

    // ==========================================
    // 6. CONTEXT & REACTIVE EXECUTION TESTS
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
