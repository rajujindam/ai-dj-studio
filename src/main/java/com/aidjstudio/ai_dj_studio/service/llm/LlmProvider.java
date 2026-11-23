package com.aidjstudio.ai_dj_studio.service.llm;

import com.aidjstudio.ai_dj_studio.dto.track.RetrievedMetadataEntry;
import reactor.core.publisher.Mono;

import java.util.List;

public interface LlmProvider {

    /**
     * Generates a grounded answer for the user's question using strictly the provided metadata evidence.
     *
     * @param trackTitle  the title of the track
     * @param trackArtist the artist of the track
     * @param question    the sanitized user question
     * @param evidence    the retrieved metadata snippets with stable source IDs
     * @return Mono emitting the generated grounded answer string
     */
    Mono<String> generateGroundedAnswer(
            String trackTitle,
            String trackArtist,
            String question,
            List<RetrievedMetadataEntry> evidence
    );
}
