package com.aidjstudio.ai_dj_studio.service.llm;

import com.aidjstudio.ai_dj_studio.dto.track.RetrievedMetadataEntry;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.exception.LlmProviderException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component("openAiCompatibleLlmProvider")
public class OpenAiCompatibleLlmProvider implements LlmProvider {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final String baseUrl;

    @Autowired
    public OpenAiCompatibleLlmProvider(
            ObjectMapper objectMapper,
            @Value("${app.llm.api-key:}") String apiKey,
            @Value("${app.llm.model:gpt-4o-mini}") String model,
            @Value("${app.llm.base-url:https://api.openai.com/v1}") String baseUrl
    ) {
        this(WebClient.builder().build(), objectMapper, apiKey, model, baseUrl);
    }

    public OpenAiCompatibleLlmProvider(
            WebClient webClient,
            ObjectMapper objectMapper,
            String apiKey,
            String model,
            String baseUrl
    ) {
        this.webClient = webClient != null ? webClient : WebClient.builder().build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.model = model != null ? model.trim() : "gpt-4o-mini";
        this.baseUrl = baseUrl != null ? baseUrl.trim() : "https://api.openai.com/v1";
    }

    @Override
    public Mono<String> generateGroundedAnswer(
            String trackTitle,
            String trackArtist,
            String question,
            List<RetrievedMetadataEntry> evidence
    ) {
        if (apiKey.isBlank()) {
            return Mono.error(new LlmProviderException("LLM API key is not configured. Set LLM_API_KEY environment variable or switch to fake provider."));
        }

        String systemPrompt = "You are the AI DJ Studio assistant. Answer listener questions about music tracks strictly using ONLY the provided metadata facts. " +
                "If the provided facts do not contain enough evidence to answer, state: 'Information not present in available metadata for this track.' " +
                "Do not guess, speculate, or introduce external facts. Always include the source ID citation (e.g. [track:1:timeline:101]) of the facts used.";

        String formattedEvidence = (evidence == null || evidence.isEmpty())
                ? "(No metadata facts available)"
                : evidence.stream()
                .map(e -> String.format("- [%s] (%s at %ds): %s", e.getSourceId(), e.getEventType(), e.getTsSeconds(), e.getText()))
                .collect(Collectors.joining("\n"));

        String userPrompt = String.format("Track: \"%s\" by %s\n\nAuthored Metadata Facts:\n%s\n\nUser Question: %s",
                trackTitle, trackArtist, formattedEvidence, question);

        return executeChatCompletion(systemPrompt, userPrompt);
    }

    @Override
    public Mono<String> generateTransitionScript(
            Track currentTrack,
            Track nextTrack,
            String matchExplanation,
            List<RetrievedMetadataEntry> evidence
    ) {
        if (apiKey.isBlank()) {
            return Mono.error(new LlmProviderException("LLM API key is not configured. Set LLM_API_KEY environment variable or switch to fake provider."));
        }

        String systemPrompt = "You are an expert AI Radio DJ host for AI DJ Studio. Your task is to announce the smooth mix/segue from the current playing track into the incoming track. " +
                "You must base your commentary strictly and ONLY on the provided musical match facts (BPM, Camelot keys) and the authored timeline facts. " +
                "Do NOT invent lore, external trivia, or false statements. If metadata facts are provided, cite the source ID (e.g. [track:1:timeline:101]). " +
                "Keep the banter energetic, crisp, and radio-ready (1-3 sentences).";

        String currTitle = currentTrack != null ? currentTrack.getTitle() : "Current Track";
        String currArtist = currentTrack != null ? currentTrack.getArtist() : "Unknown Artist";
        String nextTitle = nextTrack != null ? nextTrack.getTitle() : "Next Track";
        String nextArtist = nextTrack != null ? nextTrack.getArtist() : "Unknown Artist";

        String formattedEvidence = (evidence == null || evidence.isEmpty())
                ? "(No authored timeline facts available for these tracks)"
                : evidence.stream()
                .map(e -> String.format("- [%s] (%s at %ds): %s", e.getSourceId(), e.getEventType(), e.getTsSeconds(), e.getText()))
                .collect(Collectors.joining("\n"));

        String userPrompt = String.format(
                "Current Playing Track: \"%s\" by %s\nIncoming Selected Track: \"%s\" by %s\nMusical Match & Energy Reason: %s\n\nAuthored Metadata Evidence:\n%s\n\nAnnounce this transition:",
                currTitle, currArtist, nextTitle, nextArtist, matchExplanation, formattedEvidence
        );

        return executeChatCompletion(systemPrompt, userPrompt);
    }

    @Override
    public Mono<String> generateSoundclashScript(
            Track winningTrack,
            int voteCount,
            int totalVotes,
            List<RetrievedMetadataEntry> evidence
    ) {
        if (apiKey.isBlank()) {
            return Mono.error(new LlmProviderException("LLM API key is not configured. Set LLM_API_KEY environment variable or switch to fake provider."));
        }

        String systemPrompt = "You are an energetic AI Radio DJ host for AI DJ Studio announcing the winner of a live crowd Soundclash battle. " +
                "You must base your commentary strictly and ONLY on the winning track details, vote count, and provided authored timeline facts. " +
                "Do NOT invent lore, external trivia, or false statements. If metadata facts are provided, cite the source ID (e.g. [track:1:timeline:101]). " +
                "Keep the victory banter energetic, celebratory, and concise (1-2 sentences).";

        String title = winningTrack != null ? winningTrack.getTitle() : "Winning Track";
        String artist = winningTrack != null ? winningTrack.getArtist() : "Unknown Artist";

        String formattedEvidence = (evidence == null || evidence.isEmpty())
                ? "(No authored timeline facts available for this track)"
                : evidence.stream()
                .map(e -> String.format("- [%s] (%s at %ds): %s", e.getSourceId(), e.getEventType(), e.getTsSeconds(), e.getText()))
                .collect(Collectors.joining("\n"));

        String userPrompt = String.format(
                "Soundclash Winning Track: \"%s\" by %s\nWinning Votes: %d out of %d total votes\n\nAuthored Metadata Evidence:\n%s\n\nAnnounce the Soundclash battle winner:",
                title, artist, voteCount, totalVotes, formattedEvidence
        );

        return executeChatCompletion(systemPrompt, userPrompt);
    }

    private Mono<String> executeChatCompletion(String systemPrompt, String userPrompt) {
        Map<String, Object> requestPayload = new HashMap<>();
        requestPayload.put("model", model);
        requestPayload.put("temperature", 0.1);
        requestPayload.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));

        String endpoint = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";

        return webClient.post()
                .uri(endpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestPayload)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(10))
                .map(this::parseResponse)
                .onErrorMap(ex -> {
                    log.error("Failed to call upstream LLM provider at {}: {}", endpoint, ex.getMessage());
                    return new LlmProviderException("Upstream LLM provider failure: " + ex.getMessage(), ex);
                });
    }

    private String parseResponse(String rawJson) {
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode choices = root.get("choices");
            if (choices != null && choices.isArray() && !choices.isEmpty()) {
                JsonNode message = choices.get(0).get("message");
                if (message != null && message.has("content")) {
                    return message.get("content").asText().trim();
                }
            }
            throw new LlmProviderException("Unexpected LLM response format: missing choices[0].message.content");
        } catch (Exception e) {
            throw new LlmProviderException("Failed to parse LLM provider response: " + e.getMessage(), e);
        }
    }
}
