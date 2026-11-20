package com.aidjstudio.ai_dj_studio.service.sentiment;

import com.aidjstudio.ai_dj_studio.dto.sentiment.MessageSentiment;
import com.aidjstudio.ai_dj_studio.dto.sentiment.SentimentBucket;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Transparent, rule-based sentiment scorer using an explicit music lounge lexicon.
 * Requires no external network calls or paid API dependencies.
 *
 * Limitations:
 * - Does not detect complex sarcasm or context beyond adjacent negation tokens.
 * - Limited to pre-defined English slang and emoji vocabulary.
 */
@Component
public class LexiconSentimentScorer implements SentimentScorer {

    private static final Set<String> POSITIVE_WORDS = Set.of(
            "fire", "banger", "love", "vibes", "awesome", "amazing", "great", "good",
            "lit", "drop", "slaps", "groove", "bop", "hype", "masterpiece", "insane",
            "clean", "smooth", "epic", "heat", "beautiful", "favorite", "fav", "dancing",
            "clap", "peak", "tune", "best", "cool", "cheers", "yes", "yess", "woo", "wooo"
    );

    private static final Set<String> POSITIVE_EMOJIS = Set.of(
            "🔥", "❤️", "👏", "🎉", "🙌", "💃", "🕺", "✨", "🚀", "💯"
    );

    private static final Set<String> NEGATIVE_WORDS = Set.of(
            "boring", "trash", "skip", "bad", "hate", "awful", "terrible", "sucks",
            "worse", "worst", "mid", "kill", "stop", "wack", "noise", "horrible",
            "boo", "dead", "cringe", "mute", "slow", "annoying", "clash", "offkey",
            "ruined", "dislike", "nah"
    );

    private static final Set<String> NEGATIVE_EMOJIS = Set.of(
            "👎", "💩", "🤮", "😴", "🗑️", "🗑", "💀"
    );

    private static final Set<String> NEGATION_WORDS = Set.of(
            "not", "no", "never", "dont", "don't", "isnt", "isn't", "wont", "won't",
            "cant", "can't", "hardly", "barely"
    );

    private static final Pattern WORD_PATTERN = Pattern.compile("[a-zA-Z0-9']+|[^\\s\\w]");

    @Override
    public MessageSentiment scoreMessage(String text, Long userId, Instant timestamp) {
        if (text == null || text.trim().isEmpty()) {
            return MessageSentiment.builder()
                    .messageId(UUID.randomUUID().toString())
                    .userId(userId)
                    .text(text)
                    .score(0.0)
                    .bucket(SentimentBucket.NEUTRAL)
                    .positiveTokens(Collections.emptyList())
                    .negativeTokens(Collections.emptyList())
                    .timestamp(timestamp != null ? timestamp : Instant.now())
                    .build();
        }

        List<String> tokens = tokenize(text);
        List<String> matchedPositive = new ArrayList<>();
        List<String> matchedNegative = new ArrayList<>();

        boolean negated = false;

        for (String token : tokens) {
            String lower = token.toLowerCase(Locale.ROOT);

            if (NEGATION_WORDS.contains(lower)) {
                negated = true;
                continue;
            }

            boolean isPositive = POSITIVE_WORDS.contains(lower) || POSITIVE_EMOJIS.contains(token);
            boolean isNegative = NEGATIVE_WORDS.contains(lower) || NEGATIVE_EMOJIS.contains(token);

            if (isPositive) {
                if (negated) {
                    matchedNegative.add("not_" + token);
                } else {
                    matchedPositive.add(token);
                }
                negated = false;
            } else if (isNegative) {
                if (negated) {
                    matchedPositive.add("not_" + token); // e.g. "not bad"
                } else {
                    matchedNegative.add(token);
                }
                negated = false;
            } else {
                // If token is punctuation like comma or period, reset negation
                if (token.equals(".") || token.equals(",") || token.equals("!") || token.equals("?")) {
                    negated = false;
                }
            }
        }

        int pCount = matchedPositive.size();
        int nCount = matchedNegative.size();

        double rawScore = 0.0;
        SentimentBucket bucket = SentimentBucket.NEUTRAL;

        if (pCount > nCount) {
            rawScore = (double) (pCount - nCount) / (pCount + nCount);
            bucket = SentimentBucket.POSITIVE;
        } else if (nCount > pCount) {
            rawScore = (double) (pCount - nCount) / (pCount + nCount); // will be negative
            bucket = SentimentBucket.NEGATIVE;
        } else {
            rawScore = 0.0;
            bucket = SentimentBucket.NEUTRAL;
        }

        double roundedScore = BigDecimal.valueOf(rawScore)
                .setScale(3, RoundingMode.HALF_UP)
                .doubleValue();

        return MessageSentiment.builder()
                .messageId(UUID.randomUUID().toString())
                .userId(userId)
                .text(text)
                .score(roundedScore)
                .bucket(bucket)
                .positiveTokens(matchedPositive)
                .negativeTokens(matchedNegative)
                .timestamp(timestamp != null ? timestamp : Instant.now())
                .build();
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = WORD_PATTERN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }
}
