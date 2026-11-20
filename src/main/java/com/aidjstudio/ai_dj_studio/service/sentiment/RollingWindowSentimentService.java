package com.aidjstudio.ai_dj_studio.service.sentiment;

import com.aidjstudio.ai_dj_studio.dto.sentiment.MessageSentiment;
import com.aidjstudio.ai_dj_studio.dto.sentiment.RoomSentimentSnapshot;
import com.aidjstudio.ai_dj_studio.dto.sentiment.SentimentBucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages rolling-window sentiment tracking per lounge.
 *
 * Responsibilities:
 * - Maintains an in-memory sliding window of messages per lounge.
 * - Automatically expires messages outside the 60-second window.
 * - Caps maximum samples per lounge (default 50) to protect memory.
 * - Produces explainable room sentiment snapshots.
 */
@Slf4j
@Service
public class RollingWindowSentimentService {

    public static final int DEFAULT_WINDOW_SECONDS = 60;
    public static final int DEFAULT_MAX_SAMPLES = 50;
    public static final int LOW_SAMPLE_THRESHOLD = 3;

    private final SentimentScorer sentimentScorer;
    private final int windowSeconds;
    private final int maxSamples;

    // In-memory sliding window per lounge
    private final Map<Long, Deque<MessageSentiment>> loungeWindows = new ConcurrentHashMap<>();

    public RollingWindowSentimentService(SentimentScorer sentimentScorer) {
        this(sentimentScorer, DEFAULT_WINDOW_SECONDS, DEFAULT_MAX_SAMPLES);
    }

    public RollingWindowSentimentService(SentimentScorer sentimentScorer, int windowSeconds, int maxSamples) {
        this.sentimentScorer = sentimentScorer;
        this.windowSeconds = windowSeconds > 0 ? windowSeconds : DEFAULT_WINDOW_SECONDS;
        this.maxSamples = maxSamples > 0 ? maxSamples : DEFAULT_MAX_SAMPLES;
    }

    /**
     * Scores and records a new chat message in the lounge's rolling window.
     */
    public MessageSentiment recordMessage(Long loungeId, String text, Long userId, Instant timestamp) {
        if (loungeId == null) {
            throw new IllegalArgumentException("Lounge ID cannot be null");
        }
        Instant messageTime = timestamp != null ? timestamp : Instant.now();
        MessageSentiment scoredMessage = sentimentScorer.scoreMessage(text, userId, messageTime);

        Deque<MessageSentiment> window = loungeWindows.computeIfAbsent(loungeId, id -> new ArrayDeque<>());
        synchronized (window) {
            // Remove messages that are already expired relative to this incoming message
            evictExpiredMessages(window, messageTime);

            // Add new message to the end of the deque
            window.addLast(scoredMessage);

            // Cap queue size at maxSamples (drop oldest if overloaded)
            while (window.size() > maxSamples) {
                window.pollFirst();
            }
        }

        return scoredMessage;
    }

    public MessageSentiment recordMessage(Long loungeId, String text, Long userId) {
        return recordMessage(loungeId, text, userId, Instant.now());
    }

    /**
     * Computes the current room sentiment snapshot for the specified lounge at a specific point in time.
     */
    public RoomSentimentSnapshot getRoomSentiment(Long loungeId, Instant now) {
        if (loungeId == null) {
            throw new IllegalArgumentException("Lounge ID cannot be null");
        }
        Instant evaluationTime = now != null ? now : Instant.now();
        Instant windowStartTime = evaluationTime.minus(windowSeconds, ChronoUnit.SECONDS);

        Deque<MessageSentiment> window = loungeWindows.get(loungeId);
        List<MessageSentiment> activeMessages = new ArrayList<>();

        if (window != null) {
            synchronized (window) {
                evictExpiredMessages(window, evaluationTime);
                activeMessages.addAll(window);
            }
        }

        return buildSnapshot(loungeId, activeMessages, windowStartTime, evaluationTime);
    }

    public RoomSentimentSnapshot getRoomSentiment(Long loungeId) {
        return getRoomSentiment(loungeId, Instant.now());
    }

    public Mono<MessageSentiment> recordMessageReactive(Long loungeId, String text, Long userId) {
        return Mono.fromCallable(() -> recordMessage(loungeId, text, userId, Instant.now()));
    }

    public Mono<RoomSentimentSnapshot> getRoomSentimentReactive(Long loungeId) {
        return Mono.fromCallable(() -> getRoomSentiment(loungeId, Instant.now()));
    }

    public void clearLounge(Long loungeId) {
        if (loungeId != null) {
            loungeWindows.remove(loungeId);
        }
    }

    private void evictExpiredMessages(Deque<MessageSentiment> window, Instant now) {
        Instant cutoff = now.minus(windowSeconds, ChronoUnit.SECONDS);
        while (!window.isEmpty() && window.peekFirst().getTimestamp().isBefore(cutoff)) {
            window.pollFirst();
        }
    }

    private RoomSentimentSnapshot buildSnapshot(
            Long loungeId,
            List<MessageSentiment> activeMessages,
            Instant windowStartTime,
            Instant timestamp
    ) {
        int sampleCount = activeMessages.size();

        if (sampleCount == 0) {
            return RoomSentimentSnapshot.builder()
                    .loungeId(loungeId)
                    .roomScore(0.0)
                    .normalizedVibe(0.50)
                    .dominantBucket(SentimentBucket.NEUTRAL)
                    .totalSamples(0)
                    .positiveCount(0)
                    .neutralCount(0)
                    .negativeCount(0)
                    .windowSeconds(windowSeconds)
                    .windowStartTime(windowStartTime)
                    .timestamp(timestamp)
                    .status("EMPTY")
                    .explanation(String.format("No chat messages in rolling %ds window. Sentiment defaulted to Neutral.", windowSeconds))
                    .build();
        }

        double sumScore = 0.0;
        int positiveCount = 0;
        int neutralCount = 0;
        int negativeCount = 0;

        for (MessageSentiment msg : activeMessages) {
            sumScore += msg.getScore();
            switch (msg.getBucket()) {
                case POSITIVE -> positiveCount++;
                case NEUTRAL -> neutralCount++;
                case NEGATIVE -> negativeCount++;
            }
        }

        double rawRoomScore = sumScore / sampleCount;
        double roundedRoomScore = BigDecimal.valueOf(rawRoomScore)
                .setScale(3, RoundingMode.HALF_UP)
                .doubleValue();

        // Map [-1.0, 1.0] to [0.0, 1.0]: (score + 1.0) / 2.0
        double rawNormalizedVibe = (rawRoomScore + 1.0) / 2.0;
        double roundedNormalizedVibe = BigDecimal.valueOf(rawNormalizedVibe)
                .setScale(3, RoundingMode.HALF_UP)
                .doubleValue();

        // Determine dominant bucket
        SentimentBucket dominantBucket;
        if (positiveCount > negativeCount && positiveCount > neutralCount) {
            dominantBucket = SentimentBucket.POSITIVE;
        } else if (negativeCount > positiveCount && negativeCount > neutralCount) {
            dominantBucket = SentimentBucket.NEGATIVE;
        } else if (roundedRoomScore > 0.05) {
            dominantBucket = SentimentBucket.POSITIVE;
        } else if (roundedRoomScore < -0.05) {
            dominantBucket = SentimentBucket.NEGATIVE;
        } else {
            dominantBucket = SentimentBucket.NEUTRAL;
        }

        String status = sampleCount < LOW_SAMPLE_THRESHOLD ? "LOW_SAMPLE" : "ACTIVE";

        String explanation = String.format(
                "Room Sentiment: %.3f (Vibe: %.3f, Bucket: %s) based on %d messages in %ds window [Positive: %d, Neutral: %d, Negative: %d]. Status: %s",
                roundedRoomScore,
                roundedNormalizedVibe,
                dominantBucket,
                sampleCount,
                windowSeconds,
                positiveCount,
                neutralCount,
                negativeCount,
                status
        );

        return RoomSentimentSnapshot.builder()
                .loungeId(loungeId)
                .roomScore(roundedRoomScore)
                .normalizedVibe(roundedNormalizedVibe)
                .dominantBucket(dominantBucket)
                .totalSamples(sampleCount)
                .positiveCount(positiveCount)
                .neutralCount(neutralCount)
                .negativeCount(negativeCount)
                .windowSeconds(windowSeconds)
                .windowStartTime(windowStartTime)
                .timestamp(timestamp)
                .status(status)
                .explanation(explanation)
                .build();
    }
}
