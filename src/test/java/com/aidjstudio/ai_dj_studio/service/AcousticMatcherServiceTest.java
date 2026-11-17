package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.acoustic.AcousticMatchResult;
import com.aidjstudio.ai_dj_studio.dto.acoustic.BpmRelation;
import com.aidjstudio.ai_dj_studio.dto.acoustic.HarmonicRelation;
import com.aidjstudio.ai_dj_studio.dto.track.AcousticFeaturesDto;
import com.aidjstudio.ai_dj_studio.entity.Track;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcousticMatcherServiceTest {

    private AcousticMatcherService matcherService;

    @BeforeEach
    void setUp() {
        matcherService = new AcousticMatcherService();
    }

    // ==========================================
    // 1. BPM COMPATIBILITY TESTS
    // ==========================================

    @Nested
    @DisplayName("BPM Compatibility & Relation Tests")
    class BpmTests {

        @Test
        @DisplayName("Identical BPM should be DIRECT match")
        void identicalBpm_shouldBeDirect() {
            BigDecimal bpm1 = new BigDecimal("120.00");
            BigDecimal bpm2 = new BigDecimal("120.00");

            assertThat(matcherService.isBpmCompatible(bpm1, bpm2)).isTrue();
            assertThat(matcherService.determineBpmRelation(bpm1, bpm2)).isEqualTo(BpmRelation.DIRECT);
        }

        @Test
        @DisplayName("Direct match within ±8% boundaries (120 BPM: 110.4 to 129.6)")
        void directMatch_boundaries() {
            BigDecimal current = new BigDecimal("120.00");

            // Exact lower boundary: 120 * 0.92 = 110.40
            BigDecimal exactLower = new BigDecimal("110.40");
            assertThat(matcherService.isBpmCompatible(current, exactLower)).isTrue();
            assertThat(matcherService.determineBpmRelation(current, exactLower)).isEqualTo(BpmRelation.DIRECT);

            // Exact upper boundary: 120 * 1.08 = 129.60
            BigDecimal exactUpper = new BigDecimal("129.60");
            assertThat(matcherService.isBpmCompatible(current, exactUpper)).isTrue();
            assertThat(matcherService.determineBpmRelation(current, exactUpper)).isEqualTo(BpmRelation.DIRECT);

            // Inside boundaries
            assertThat(matcherService.isBpmCompatible(current, new BigDecimal("115.00"))).isTrue();
            assertThat(matcherService.isBpmCompatible(current, new BigDecimal("125.00"))).isTrue();

            // Just outside boundaries
            BigDecimal belowLower = new BigDecimal("110.39");
            BigDecimal aboveUpper = new BigDecimal("129.61");
            assertThat(matcherService.isBpmCompatible(current, belowLower)).isFalse();
            assertThat(matcherService.determineBpmRelation(current, belowLower)).isEqualTo(BpmRelation.INCOMPATIBLE);
            assertThat(matcherService.isBpmCompatible(current, aboveUpper)).isFalse();
            assertThat(matcherService.determineBpmRelation(current, aboveUpper)).isEqualTo(BpmRelation.INCOMPATIBLE);
        }

        @Test
        @DisplayName("Half-time (0.5x) match within ±8% (140 BPM -> 70 BPM: 64.4 to 75.6)")
        void halfTime_boundaries() {
            BigDecimal current = new BigDecimal("140.00");

            // Clean 0.5x target
            BigDecimal exactHalf = new BigDecimal("70.00");
            assertThat(matcherService.isBpmCompatible(current, exactHalf)).isTrue();
            assertThat(matcherService.determineBpmRelation(current, exactHalf)).isEqualTo(BpmRelation.HALF_TIME);

            // Half-time lower boundary: 70 * 0.92 = 64.40
            BigDecimal lowerBoundary = new BigDecimal("64.40");
            assertThat(matcherService.isBpmCompatible(current, lowerBoundary)).isTrue();
            assertThat(matcherService.determineBpmRelation(current, lowerBoundary)).isEqualTo(BpmRelation.HALF_TIME);

            // Half-time upper boundary: 70 * 1.08 = 75.60
            BigDecimal upperBoundary = new BigDecimal("75.60");
            assertThat(matcherService.isBpmCompatible(current, upperBoundary)).isTrue();
            assertThat(matcherService.determineBpmRelation(current, upperBoundary)).isEqualTo(BpmRelation.HALF_TIME);

            // Just outside half-time boundaries
            assertThat(matcherService.isBpmCompatible(current, new BigDecimal("64.39"))).isFalse();
            assertThat(matcherService.isBpmCompatible(current, new BigDecimal("75.61"))).isFalse();
        }

        @Test
        @DisplayName("Double-time (2.0x) match within ±8% (70 BPM -> 140 BPM: 128.8 to 151.2)")
        void doubleTime_boundaries() {
            BigDecimal current = new BigDecimal("70.00");

            // Clean 2.0x target
            BigDecimal exactDouble = new BigDecimal("140.00");
            assertThat(matcherService.isBpmCompatible(current, exactDouble)).isTrue();
            assertThat(matcherService.determineBpmRelation(current, exactDouble)).isEqualTo(BpmRelation.DOUBLE_TIME);

            // Double-time lower boundary: 140 * 0.92 = 128.80
            BigDecimal lowerBoundary = new BigDecimal("128.80");
            assertThat(matcherService.isBpmCompatible(current, lowerBoundary)).isTrue();
            assertThat(matcherService.determineBpmRelation(current, lowerBoundary)).isEqualTo(BpmRelation.DOUBLE_TIME);

            // Double-time upper boundary: 140 * 1.08 = 151.20
            BigDecimal upperBoundary = new BigDecimal("151.20");
            assertThat(matcherService.isBpmCompatible(current, upperBoundary)).isTrue();
            assertThat(matcherService.determineBpmRelation(current, upperBoundary)).isEqualTo(BpmRelation.DOUBLE_TIME);

            // Just outside double-time boundaries
            assertThat(matcherService.isBpmCompatible(current, new BigDecimal("128.79"))).isFalse();
            assertThat(matcherService.isBpmCompatible(current, new BigDecimal("151.21"))).isFalse();
        }

        @Test
        @DisplayName("Custom tolerance percentage works correctly")
        void customTolerance() {
            BigDecimal current = new BigDecimal("100.00");
            // 5% tolerance: [95.0, 105.0]
            assertThat(matcherService.isBpmCompatible(current, new BigDecimal("96.00"), 5.0)).isTrue();
            assertThat(matcherService.isBpmCompatible(current, new BigDecimal("94.00"), 5.0)).isFalse();
        }

        @Test
        @DisplayName("Invalid BPM values throw IllegalArgumentException")
        void invalidBpm_throwsException() {
            BigDecimal valid = new BigDecimal("120.00");
            BigDecimal zero = BigDecimal.ZERO;
            BigDecimal negative = new BigDecimal("-10.00");

            assertThatThrownBy(() -> matcherService.determineBpmRelation(null, valid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Current BPM must be a positive number");

            assertThatThrownBy(() -> matcherService.determineBpmRelation(valid, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Candidate BPM must be a positive number");

            assertThatThrownBy(() -> matcherService.determineBpmRelation(zero, valid))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> matcherService.determineBpmRelation(valid, negative))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> matcherService.determineBpmRelation(valid, valid, -1.0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be negative");
        }
    }

    // ==========================================
    // 2. CAMELOT KEY COMPATIBILITY TESTS
    // ==========================================

    @Nested
    @DisplayName("Camelot Key Compatibility & Harmonic Relation Tests")
    class CamelotTests {

        @ParameterizedTest(name = "Same key {0} -> {0} should be SAME_KEY")
        @ValueSource(strings = {"1A", "8A", "12A", "1B", "8B", "12B"})
        void sameKey_shouldBeSameKey(String key) {
            assertThat(matcherService.isCamelotCompatible(key, key)).isTrue();
            assertThat(matcherService.determineHarmonicRelation(key, key)).isEqualTo(HarmonicRelation.SAME_KEY);
        }

        @ParameterizedTest(name = "Adjacent key: {0} -> {1}")
        @CsvSource({
                "8A, 7A",
                "8A, 9A",
                "8B, 7B",
                "8B, 9B",
                "5A, 4A",
                "5A, 6A",
                "11B, 10B",
                "11B, 12B"
        })
        void adjacentKey_noWrap(String key1, String key2) {
            assertThat(matcherService.isCamelotCompatible(key1, key2)).isTrue();
            assertThat(matcherService.determineHarmonicRelation(key1, key2)).isEqualTo(HarmonicRelation.ADJACENT_KEY);
        }

        @ParameterizedTest(name = "Wraparound adjacent key: {0} <-> {1}")
        @CsvSource({
                "1A, 12A",
                "12A, 1A",
                "1B, 12B",
                "12B, 1B"
        })
        void adjacentKey_wraparound(String key1, String key2) {
            assertThat(matcherService.isCamelotCompatible(key1, key2)).isTrue();
            assertThat(matcherService.determineHarmonicRelation(key1, key2)).isEqualTo(HarmonicRelation.ADJACENT_KEY);
        }

        @ParameterizedTest(name = "Relative major/minor: {0} <-> {1}")
        @CsvSource({
                "8A, 8B",
                "8B, 8A",
                "1A, 1B",
                "1B, 1A",
                "12A, 12B",
                "12B, 12A",
                "4A, 4B"
        })
        void relativeMajorMinor_sameNumberOppositeLetter(String key1, String key2) {
            assertThat(matcherService.isCamelotCompatible(key1, key2)).isTrue();
            assertThat(matcherService.determineHarmonicRelation(key1, key2)).isEqualTo(HarmonicRelation.RELATIVE_KEY);
        }

        @ParameterizedTest(name = "Harmonic Clashes: {0} vs {1}")
        @CsvSource({
                "8A, 10A",     // +2 on same letter
                "8A, 6A",      // -2 on same letter
                "8A, 9B",      // Adjacent number, opposite letter (clash)
                "8A, 7B",      // Adjacent number, opposite letter (clash)
                "1A, 11A",     // Distance 2 across wraparound
                "12A, 2A",     // Distance 2 across wraparound
                "1A, 12B",     // Wraparound with opposite letter
                "8A, 2A",      // Distant key
                "8A, 2B",      // Distant opposite key
                "1A, 7A",      // Tritone clash
                "1A, 7B"       // Tritone opposite letter clash
        })
        void harmonicClashes(String key1, String key2) {
            assertThat(matcherService.isCamelotCompatible(key1, key2)).isFalse();
            assertThat(matcherService.determineHarmonicRelation(key1, key2)).isEqualTo(HarmonicRelation.CLASH);
        }

        @Test
        @DisplayName("Case-insensitivity and whitespace trimming for Camelot keys")
        void caseInsensitiveAndTrim() {
            assertThat(matcherService.isCamelotCompatible(" 8a ", "8A")).isTrue();
            assertThat(matcherService.determineHarmonicRelation("8a", "8B")).isEqualTo(HarmonicRelation.RELATIVE_KEY);
            assertThat(matcherService.validateAndNormalizeCamelotKey(" 12b ")).isEqualTo("12B");
        }

        @ParameterizedTest(name = "Invalid Camelot key ''{0}'' should throw IllegalArgumentException")
        @ValueSource(strings = {
                "", "   ", "0A", "13A", "14B", "8C", "8Z", "ABC", "123", "08A", "A8", "null"
        })
        void invalidCamelotKeys_throwException(String invalidKey) {
            assertThatThrownBy(() -> matcherService.validateAndNormalizeCamelotKey(invalidKey))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> matcherService.determineHarmonicRelation("8A", invalidKey))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Null Camelot key throws IllegalArgumentException")
        void nullCamelotKey_throwsException() {
            assertThatThrownBy(() -> matcherService.validateAndNormalizeCamelotKey(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be null");
        }
    }

    // ==========================================
    // 3. COMBINED EVALUATE MATCH TESTS
    // ==========================================

    @Nested
    @DisplayName("Combined Acoustic Match Evaluation Tests")
    class EvaluateMatchTests {

        @Test
        @DisplayName("Compatible: direct BPM + same key")
        void compatible_directBpm_sameKey() {
            AcousticMatchResult result = matcherService.evaluateMatch(
                    new BigDecimal("120.00"), "8A",
                    new BigDecimal("124.00"), "8A"
            );

            assertThat(result.isCompatible()).isTrue();
            assertThat(result.isBpmCompatible()).isTrue();
            assertThat(result.isCamelotCompatible()).isTrue();
            assertThat(result.getBpmRelation()).isEqualTo(BpmRelation.DIRECT);
            assertThat(result.getHarmonicRelation()).isEqualTo(HarmonicRelation.SAME_KEY);
            assertThat(result.getBpmDelta()).isEqualByComparingTo("4.00");
            assertThat(result.getBpmDeltaPercent()).isEqualByComparingTo("3.33");
            assertThat(result.getReason()).contains("Compatible segue");
        }

        @Test
        @DisplayName("Compatible: half-time BPM + relative key")
        void compatible_halfTime_relativeKey() {
            AcousticMatchResult result = matcherService.evaluateMatch(
                    new BigDecimal("140.00"), "8A",
                    new BigDecimal("70.00"), "8B"
            );

            assertThat(result.isCompatible()).isTrue();
            assertThat(result.isBpmCompatible()).isTrue();
            assertThat(result.isCamelotCompatible()).isTrue();
            assertThat(result.getBpmRelation()).isEqualTo(BpmRelation.HALF_TIME);
            assertThat(result.getHarmonicRelation()).isEqualTo(HarmonicRelation.RELATIVE_KEY);
            assertThat(result.getReason()).contains("Compatible segue");
        }

        @Test
        @DisplayName("Compatible: double-time BPM + wraparound adjacent key (12A -> 1A)")
        void compatible_doubleTime_wraparoundAdjacentKey() {
            AcousticMatchResult result = matcherService.evaluateMatch(
                    new BigDecimal("70.00"), "12A",
                    new BigDecimal("140.00"), "1A"
            );

            assertThat(result.isCompatible()).isTrue();
            assertThat(result.isBpmCompatible()).isTrue();
            assertThat(result.isCamelotCompatible()).isTrue();
            assertThat(result.getBpmRelation()).isEqualTo(BpmRelation.DOUBLE_TIME);
            assertThat(result.getHarmonicRelation()).isEqualTo(HarmonicRelation.ADJACENT_KEY);
        }

        @Test
        @DisplayName("Incompatible: BPM out of tolerance + harmonic key compatible")
        void incompatible_bpmOut_keyCompatible() {
            AcousticMatchResult result = matcherService.evaluateMatch(
                    new BigDecimal("120.00"), "8A",
                    new BigDecimal("160.00"), "9A"
            );

            assertThat(result.isCompatible()).isFalse();
            assertThat(result.isBpmCompatible()).isFalse();
            assertThat(result.isCamelotCompatible()).isTrue();
            assertThat(result.getBpmRelation()).isEqualTo(BpmRelation.INCOMPATIBLE);
            assertThat(result.getHarmonicRelation()).isEqualTo(HarmonicRelation.ADJACENT_KEY);
            assertThat(result.getReason()).contains("Incompatible segue: BPM out of tolerance");
        }

        @Test
        @DisplayName("Incompatible: BPM compatible + harmonic key clash")
        void incompatible_bpmCompatible_keyClash() {
            AcousticMatchResult result = matcherService.evaluateMatch(
                    new BigDecimal("120.00"), "8A",
                    new BigDecimal("122.00"), "10B"
            );

            assertThat(result.isCompatible()).isFalse();
            assertThat(result.isBpmCompatible()).isTrue();
            assertThat(result.isCamelotCompatible()).isFalse();
            assertThat(result.getBpmRelation()).isEqualTo(BpmRelation.DIRECT);
            assertThat(result.getHarmonicRelation()).isEqualTo(HarmonicRelation.CLASH);
            assertThat(result.getReason()).contains("Harmonic key clash");
        }

        @Test
        @DisplayName("Incompatible: both BPM and key clash")
        void incompatible_bothFail() {
            AcousticMatchResult result = matcherService.evaluateMatch(
                    new BigDecimal("120.00"), "8A",
                    new BigDecimal("180.00"), "3B"
            );

            assertThat(result.isCompatible()).isFalse();
            assertThat(result.isBpmCompatible()).isFalse();
            assertThat(result.isCamelotCompatible()).isFalse();
            assertThat(result.getReason()).contains("BPM out of tolerance");
            assertThat(result.getReason()).contains("Harmonic key clash");
        }

        @Test
        @DisplayName("Overload with Track entities")
        void evaluateMatch_trackEntityOverload() {
            Track t1 = new Track();
            t1.setId(1L);
            t1.setBpm(new BigDecimal("128.00"));
            t1.setCamelotKey("8A");

            Track t2 = new Track();
            t2.setId(2L);
            t2.setBpm(new BigDecimal("125.00"));
            t2.setCamelotKey("7A");

            AcousticMatchResult result = matcherService.evaluateMatch(t1, t2);
            assertThat(result.isCompatible()).isTrue();
            assertThat(result.getHarmonicRelation()).isEqualTo(HarmonicRelation.ADJACENT_KEY);
        }

        @Test
        @DisplayName("Overload with AcousticFeaturesDto")
        void evaluateMatch_acousticFeaturesDtoOverload() {
            AcousticFeaturesDto dto1 = AcousticFeaturesDto.builder()
                    .bpm(new BigDecimal("128.00"))
                    .camelotKey("8A")
                    .build();

            AcousticFeaturesDto dto2 = AcousticFeaturesDto.builder()
                    .bpm(new BigDecimal("128.00"))
                    .camelotKey("8B")
                    .build();

            AcousticMatchResult result = matcherService.evaluateMatch(dto1, dto2);
            assertThat(result.isCompatible()).isTrue();
            assertThat(result.getHarmonicRelation()).isEqualTo(HarmonicRelation.RELATIVE_KEY);
        }
    }
}
