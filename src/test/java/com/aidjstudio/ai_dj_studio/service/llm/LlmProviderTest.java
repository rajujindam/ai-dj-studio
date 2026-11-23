package com.aidjstudio.ai_dj_studio.service.llm;

import com.aidjstudio.ai_dj_studio.dto.track.RetrievedMetadataEntry;
import com.aidjstudio.ai_dj_studio.exception.LlmProviderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmProviderTest {

    @Test
    @DisplayName("FakeLlmProvider should generate grounded answer citing source IDs")
    void fakeLlmProvider_shouldGenerateGroundedAnswer() {
        FakeLlmProvider provider = new FakeLlmProvider();

        RetrievedMetadataEntry entry = RetrievedMetadataEntry.builder()
                .id(1L)
                .trackId(10L)
                .sourceId("track:10:timeline:1")
                .tsSeconds(15)
                .eventType("sample_credit")
                .text("Sampled 1970s funk break.")
                .score(5.0)
                .build();

        StepVerifier.create(provider.generateGroundedAnswer("Funky Beats", "DJ Alpha", "What is sampled?", List.of(entry)))
                .assertNext(answer -> {
                    assertTrue(answer.contains("Funky Beats"));
                    assertTrue(answer.contains("DJ Alpha"));
                    assertTrue(answer.contains("[track:10:timeline:1]"));
                    assertTrue(answer.contains("Sampled 1970s funk break."));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("FakeLlmProvider should return cannot-answer message when evidence is empty")
    void fakeLlmProvider_emptyEvidence_shouldReturnCannotAnswer() {
        FakeLlmProvider provider = new FakeLlmProvider();

        StepVerifier.create(provider.generateGroundedAnswer("Funky Beats", "DJ Alpha", "Who produced?", Collections.emptyList()))
                .assertNext(answer -> {
                    assertEquals("Information not present in available metadata for this track.", answer);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("FakeLlmProvider should simulate provider failure when flag is set")
    void fakeLlmProvider_simulateFailure_shouldFail() {
        FakeLlmProvider provider = new FakeLlmProvider();
        provider.setSimulateFailure(true);

        StepVerifier.create(provider.generateGroundedAnswer("Funky Beats", "DJ Alpha", "What is sampled?", List.of()))
                .expectError(LlmProviderException.class)
                .verify();
    }

    @Test
    @DisplayName("OpenAiCompatibleLlmProvider should fail fast when API key is missing")
    void openAiProvider_missingApiKey_shouldFailFast() {
        OpenAiCompatibleLlmProvider provider = new OpenAiCompatibleLlmProvider(
                new ObjectMapper(),
                "",
                "gpt-4o-mini",
                "https://api.openai.com/v1"
        );

        StepVerifier.create(provider.generateGroundedAnswer("Funky Beats", "DJ Alpha", "What is sampled?", List.of()))
                .expectErrorMatches(t -> t instanceof LlmProviderException && t.getMessage().contains("LLM API key is not configured"))
                .verify();
    }
}
