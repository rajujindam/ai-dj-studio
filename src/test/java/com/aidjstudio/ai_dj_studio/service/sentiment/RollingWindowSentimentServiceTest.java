package com.aidjstudio.ai_dj_studio.service.sentiment;

import com.aidjstudio.ai_dj_studio.dto.sentiment.MessageSentiment;
import com.aidjstudio.ai_dj_studio.dto.sentiment.RoomSentimentSnapshot;
import com.aidjstudio.ai_dj_studio.dto.sentiment.SentimentBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RollingWindowSentimentServiceTest {

    private LexiconSentimentScorer scorer;
    private RollingWindowSentimentService sentimentService;

    @BeforeEach
    void setUp() {
        scorer = new LexiconSentimentScorer();
        sentimentService = new RollingWindowSentimentService(scorer, 60, 50);
    }

    // ==========================================
    // 1. INDIVIDUAL BUCKET SCORING TESTS
    // ==========================================

    @Nested
    @DisplayName("Lexicon Scorer & Sentiment Bucket Tests")
    class BucketScoringTests {

        @ParameterizedTest(name = "Positive message: \"{0}\"")
        @ValueSource(strings = {
                "This track is fire",
                "What a banger! Love this tune",
                "Awesome vibes in the lounge",
                "Clean drop, amazing transition 🔥",
                "Slaps so hard ❤️"
        })
        void positiveBucket_classifiesCorrectly(String text) {
            MessageSentiment msg = scorer.scoreMessage(text);
            assertThat(msg.getBucket()).isEqualTo(SentimentBucket.POSITIVE);
            assertThat(msg.getScore()).isGreaterThan(0.0);
            assertThat(msg.getPositiveTokens()).isNotEmpty();
        }

        @ParameterizedTest(name = "Negative message: \"{0}\"")
        @ValueSource(strings = {
                "This track is boring",
                "Total trash, please skip",
                "Awful song, terrible transition",
                "Mid beat, hate this 👎",
                "Horrible clash 💩"
        })
        void negativeBucket_classifiesCorrectly(String text) {
            MessageSentiment msg = scorer.scoreMessage(text);
            assertThat(msg.getBucket()).isEqualTo(SentimentBucket.NEGATIVE);
            assertThat(msg.getScore()).isLessThan(0.0);
            assertThat(msg.getNegativeTokens()).isNotEmpty();
        }

        @ParameterizedTest(name = "Neutral message: \"{0}\"")
        @ValueSource(strings = {
                "Who is the artist for this track?",
                "What is the BPM right now?",
                "Hey everyone",
                "Just joined the lounge"
        })
        void neutralBucket_classifiesCorrectly(String text) {
            MessageSentiment msg = scorer.scoreMessage(text);
            assertThat(msg.getBucket()).isEqualTo(SentimentBucket.NEUTRAL);
            assertThat(msg.getScore()).isEqualTo(0.0);
            assertThat(msg.getPositiveTokens()).isEmpty();
            assertThat(msg.getNegativeTokens()).isEmpty();
        }

        @Test
        @DisplayName("Negation handling: 'not good' flips to negative, 'not bad' flips to positive")
        void negation_flipsSentiment() {
            MessageSentiment notGood = scorer.scoreMessage("This mix is not good");
            assertThat(notGood.getBucket()).isEqualTo(SentimentBucket.NEGATIVE);
            assertThat(notGood.getScore()).isLessThan(0.0);

            MessageSentiment notBad = scorer.scoreMessage("The transition was not bad");
            assertThat(notBad.getBucket()).isEqualTo(SentimentBucket.POSITIVE);
            assertThat(notBad.getScore()).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("Balanced positive and negative words yield neutral score")
        void equalPositiveAndNegative_isNeutral() {
            // "Love" (+1) and "trash" (-1)
            MessageSentiment msg = scorer.scoreMessage("Love the artist but the mix is trash");
            assertThat(msg.getBucket()).isEqualTo(SentimentBucket.NEUTRAL);
            assertThat(msg.getScore()).isEqualTo(0.0);
        }
    }

    // ==========================================
    // 2. EMPTY WINDOW TESTS
    // ==========================================

    @Nested
    @DisplayName("Empty Window & Low Sample Tests")
    class EmptyWindowTests {

        @Test
        @DisplayName("Empty lounge window returns neutral 0.0 score and 0.50 normalized vibe")
        void emptyWindow_returnsNeutralDefault() {
            RoomSentimentSnapshot snapshot = sentimentService.getRoomSentiment(100L);

            assertThat(snapshot.getLoungeId()).isEqualTo(100L);
            assertThat(snapshot.getRoomScore()).isEqualTo(0.0);
            assertThat(snapshot.getNormalizedVibe()).isEqualTo(0.50);
            assertThat(snapshot.getDominantBucket()).isEqualTo(SentimentBucket.NEUTRAL);
            assertThat(snapshot.getTotalSamples()).isEqualTo(0);
            assertThat(snapshot.getPositiveCount()).isEqualTo(0);
            assertThat(snapshot.getNeutralCount()).isEqualTo(0);
            assertThat(snapshot.getNegativeCount()).isEqualTo(0);
            assertThat(snapshot.getStatus()).isEqualTo("EMPTY");
            assertThat(snapshot.getExplanation()).contains("No chat messages in rolling 60s window");
        }

        @Test
        @DisplayName("Fewer than 3 messages returns status LOW_SAMPLE")
        void lowSample_returnsLowSampleStatus() {
            Long loungeId = 1L;
            sentimentService.recordMessage(loungeId, "Fire tune 🔥", 10L);

            RoomSentimentSnapshot snapshot = sentimentService.getRoomSentiment(loungeId);
            assertThat(snapshot.getTotalSamples()).isEqualTo(1);
            assertThat(snapshot.getStatus()).isEqualTo("LOW_SAMPLE");
            assertThat(snapshot.getDominantBucket()).isEqualTo(SentimentBucket.POSITIVE);
        }

        @Test
        @DisplayName("Null lounge ID throws IllegalArgumentException")
        void nullLoungeId_throwsException() {
            assertThatThrownBy(() -> sentimentService.recordMessage(null, "Test", 1L))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> sentimentService.getRoomSentiment(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==========================================
    // 3. MIXED MESSAGES TESTS
    // ==========================================

    @Nested
    @DisplayName("Mixed Messages & Aggregate Scoring Tests")
    class MixedMessagesTests {

        @Test
        @DisplayName("Mixed messages produce correct sample counts and weighted average")
        void mixedMessages_averagesCorrectly() {
            Long loungeId = 10L;
            Instant now = Instant.parse("2026-11-20T12:00:00Z");

            // 3 positive (+1.0 each)
            sentimentService.recordMessage(loungeId, "Fire banger!", 1L, now.minusSeconds(30));
            sentimentService.recordMessage(loungeId, "Awesome drop!", 2L, now.minusSeconds(20));
            sentimentService.recordMessage(loungeId, "Love this groove ❤️", 3L, now.minusSeconds(10));

            // 1 neutral (0.0)
            sentimentService.recordMessage(loungeId, "What's the track ID?", 4L, now.minusSeconds(5));

            // 1 negative (-1.0)
            sentimentService.recordMessage(loungeId, "Boring intro", 5L, now);

            RoomSentimentSnapshot snapshot = sentimentService.getRoomSentiment(loungeId, now);

            // Total samples = 5 (>= 3 -> ACTIVE)
            assertThat(snapshot.getTotalSamples()).isEqualTo(5);
            assertThat(snapshot.getPositiveCount()).isEqualTo(3);
            assertThat(snapshot.getNeutralCount()).isEqualTo(1);
            assertThat(snapshot.getNegativeCount()).isEqualTo(1);
            assertThat(snapshot.getStatus()).isEqualTo("ACTIVE");

            // Average score: (1.0 + 1.0 + 1.0 + 0.0 - 1.0) / 5 = 2.0 / 5 = +0.400
            assertThat(snapshot.getRoomScore()).isEqualTo(0.400);

            // Normalized vibe: (0.400 + 1.0) / 2.0 = 0.700
            assertThat(snapshot.getNormalizedVibe()).isEqualTo(0.700);
            assertThat(snapshot.getDominantBucket()).isEqualTo(SentimentBucket.POSITIVE);
            assertThat(snapshot.getExplanation()).contains("Positive: 3, Neutral: 1, Negative: 1");
        }
    }

    // ==========================================
    // 4. TIME / WINDOW EXPIRY TESTS
    // ==========================================

    @Nested
    @DisplayName("Rolling Window Expiry Tests")
    class TimeWindowExpiryTests {

        @Test
        @DisplayName("Messages older than 60s expire and drop out of the room sentiment")
        void messagesOlderThan60s_expire() {
            Long loungeId = 20L;
            Instant t0 = Instant.parse("2026-11-20T12:00:00Z");

            // Message sent at t0
            sentimentService.recordMessage(loungeId, "Fire track 🔥", 1L, t0);

            // At t0 + 30s: message is 30s old (< 60s) -> still active
            Instant t30 = t0.plus(30, ChronoUnit.SECONDS);
            RoomSentimentSnapshot snap30 = sentimentService.getRoomSentiment(loungeId, t30);
            assertThat(snap30.getTotalSamples()).isEqualTo(1);
            assertThat(snap30.getRoomScore()).isGreaterThan(0.0);

            // At t0 + 61s: message is 61s old (> 60s) -> expired!
            Instant t61 = t0.plus(61, ChronoUnit.SECONDS);
            RoomSentimentSnapshot snap61 = sentimentService.getRoomSentiment(loungeId, t61);
            assertThat(snap61.getTotalSamples()).isEqualTo(0);
            assertThat(snap61.getRoomScore()).isEqualTo(0.0);
            assertThat(snap61.getNormalizedVibe()).isEqualTo(0.50);
            assertThat(snap61.getStatus()).isEqualTo("EMPTY");
        }

        @Test
        @DisplayName("Sliding eviction keeps only messages within the rolling 60s window")
        void slidingEviction_keepsRecentOnly() {
            Long loungeId = 21L;
            Instant t0 = Instant.parse("2026-11-20T12:00:00Z");

            // Sent at t0 (will be 70s old at t70 -> expires)
            sentimentService.recordMessage(loungeId, "Fire!", 1L, t0);

            // Sent at t0 + 40s (will be 30s old at t70 -> remains active)
            sentimentService.recordMessage(loungeId, "Love this banger!", 2L, t0.plusSeconds(40));

            // Evaluate at t0 + 70s
            Instant t70 = t0.plus(70, ChronoUnit.SECONDS);
            RoomSentimentSnapshot snap = sentimentService.getRoomSentiment(loungeId, t70);

            assertThat(snap.getTotalSamples()).isEqualTo(1);
            assertThat(snap.getPositiveCount()).isEqualTo(1);
            assertThat(snap.getRoomScore()).isEqualTo(1.0);
        }
    }

    // ==========================================
    // 5. TONE SHIFT TESTS
    // ==========================================

    @Nested
    @DisplayName("Tone Shift Tests")
    class ToneShiftTests {

        @Test
        @DisplayName("Deliberate tone shift from positive to negative visibly shifts room score")
        void positiveToNegativeToneShift_shiftsRoomScore() {
            Long loungeId = 30L;
            Instant t0 = Instant.parse("2026-11-20T12:00:00Z");

            // Phase 1: Crowd is loving it (3 positive messages at t0)
            sentimentService.recordMessage(loungeId, "Fire track!", 1L, t0);
            sentimentService.recordMessage(loungeId, "Love this banger!", 2L, t0.plusSeconds(5));
            sentimentService.recordMessage(loungeId, "Amazing vibes 🔥", 3L, t0.plusSeconds(10));

            // Snapshot at t0 + 15s
            RoomSentimentSnapshot phase1 = sentimentService.getRoomSentiment(loungeId, t0.plusSeconds(15));
            assertThat(phase1.getDominantBucket()).isEqualTo(SentimentBucket.POSITIVE);
            assertThat(phase1.getRoomScore()).isEqualTo(1.000);
            assertThat(phase1.getNormalizedVibe()).isEqualTo(1.000);

            // Phase 2: A sudden bad transition occurs at t0 + 20s! Crowd turns negative
            sentimentService.recordMessage(loungeId, "Trash transition", 4L, t0.plusSeconds(20));
            sentimentService.recordMessage(loungeId, "Horrible clash, skip!", 5L, t0.plusSeconds(25));
            sentimentService.recordMessage(loungeId, "Awful boring beat 👎", 6L, t0.plusSeconds(30));
            sentimentService.recordMessage(loungeId, "Terrible mid sound", 7L, t0.plusSeconds(35));

            // Snapshot at t0 + 40s (both positive and negative are active: 3 positive vs 4 negative)
            RoomSentimentSnapshot phase2 = sentimentService.getRoomSentiment(loungeId, t0.plusSeconds(40));
            assertThat(phase2.getTotalSamples()).isEqualTo(7);
            // 3 positive (+3.0) and 4 negative (-4.0) -> sum = -1.0 / 7 = -0.143
            assertThat(phase2.getRoomScore()).isLessThan(0.0);
            assertThat(phase2.getDominantBucket()).isEqualTo(SentimentBucket.NEGATIVE);

            // Phase 3: At t0 + 75s (old positive messages from t0..t10 have expired, only negative remain!)
            RoomSentimentSnapshot phase3 = sentimentService.getRoomSentiment(loungeId, t0.plusSeconds(75));
            // Only messages from t20..t35 are <= 60s old
            assertThat(phase3.getTotalSamples()).isEqualTo(4);
            assertThat(phase3.getPositiveCount()).isEqualTo(0);
            assertThat(phase3.getNegativeCount()).isEqualTo(4);
            assertThat(phase3.getRoomScore()).isEqualTo(-1.000);
            assertThat(phase3.getNormalizedVibe()).isEqualTo(0.000);
            assertThat(phase3.getDominantBucket()).isEqualTo(SentimentBucket.NEGATIVE);
        }
    }

    // ==========================================
    // 6. REACTIVE EXECUTION TESTS
    // ==========================================

    @Nested
    @DisplayName("Reactive Execution Tests")
    class ReactiveTests {

        @Test
        @DisplayName("recordMessageReactive returns Mono<MessageSentiment>")
        void reactiveRecord_works() {
            StepVerifier.create(sentimentService.recordMessageReactive(50L, "Fire drop 🔥", 1L))
                    .assertNext(msg -> {
                        assertThat(msg.getBucket()).isEqualTo(SentimentBucket.POSITIVE);
                        assertThat(msg.getScore()).isGreaterThan(0.0);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("getRoomSentimentReactive returns Mono<RoomSentimentSnapshot>")
        void reactiveSnapshot_works() {
            sentimentService.recordMessage(60L, "Love it ❤️", 1L);

            StepVerifier.create(sentimentService.getRoomSentimentReactive(60L))
                    .assertNext(snap -> {
                        assertThat(snap.getLoungeId()).isEqualTo(60L);
                        assertThat(snap.getTotalSamples()).isEqualTo(1);
                        assertThat(snap.getDominantBucket()).isEqualTo(SentimentBucket.POSITIVE);
                    })
                    .verifyComplete();
        }
    }
}
