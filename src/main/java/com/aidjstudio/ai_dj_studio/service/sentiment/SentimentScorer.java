package com.aidjstudio.ai_dj_studio.service.sentiment;

import com.aidjstudio.ai_dj_studio.dto.sentiment.MessageSentiment;

import java.time.Instant;

/**
 * Pluggable contract for scoring individual chat message sentiment.
 * Allows replacing the rule/lexicon scorer with an AI/ML provider later.
 */
public interface SentimentScorer {

    MessageSentiment scoreMessage(String text, Long userId, Instant timestamp);

    default MessageSentiment scoreMessage(String text) {
        return scoreMessage(text, null, Instant.now());
    }
}
