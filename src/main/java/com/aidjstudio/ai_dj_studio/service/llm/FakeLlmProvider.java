package com.aidjstudio.ai_dj_studio.service.llm;

import com.aidjstudio.ai_dj_studio.dto.track.RetrievedMetadataEntry;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.exception.LlmProviderException;
import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

@Component("fakeLlmProvider")
@Getter
@Setter
public class FakeLlmProvider implements LlmProvider {

    private boolean simulateFailure = false;
    private String customResponse = null;

    @Override
    public Mono<String> generateGroundedAnswer(
            String trackTitle,
            String trackArtist,
            String question,
            List<RetrievedMetadataEntry> evidence
    ) {
        if (simulateFailure) {
            return Mono.error(new LlmProviderException("Simulated upstream LLM provider failure"));
        }

        if (customResponse != null) {
            return Mono.just(customResponse);
        }

        if (evidence == null || evidence.isEmpty()) {
            return Mono.just("Information not present in available metadata for this track.");
        }

        // Formulate deterministic grounded response citing source IDs
        String citations = evidence.stream()
                .map(e -> String.format("[%s]: %s", e.getSourceId(), e.getText()))
                .collect(Collectors.joining(" "));

        String answer = String.format("For \"%s\" by %s: %s", trackTitle, trackArtist, citations);
        return Mono.just(answer);
    }

    @Override
    public Mono<String> generateTransitionScript(
            Track currentTrack,
            Track nextTrack,
            String matchExplanation,
            List<RetrievedMetadataEntry> evidence
    ) {
        if (simulateFailure) {
            return Mono.error(new LlmProviderException("Simulated upstream LLM provider failure"));
        }

        if (customResponse != null) {
            return Mono.just(customResponse);
        }

        String currTitle = currentTrack != null ? currentTrack.getTitle() : "Current Track";
        String currArtist = currentTrack != null ? currentTrack.getArtist() : "Unknown Artist";
        String nextTitle = nextTrack != null ? nextTrack.getTitle() : "Next Track";
        String nextArtist = nextTrack != null ? nextTrack.getArtist() : "Unknown Artist";

        if (evidence == null || evidence.isEmpty()) {
            return Mono.just(String.format(
                    "Transitioning from \"%s\" by %s into \"%s\" by %s. %s",
                    currTitle, currArtist, nextTitle, nextArtist, matchExplanation
            ));
        }

        String citations = evidence.stream()
                .map(e -> String.format("[%s]: %s", e.getSourceId(), e.getText()))
                .collect(Collectors.joining(" "));

        return Mono.just(String.format(
                "Mixing from \"%s\" by %s into \"%s\" by %s. %s %s",
                currTitle, currArtist, nextTitle, nextArtist, matchExplanation, citations
        ));
    }
}
