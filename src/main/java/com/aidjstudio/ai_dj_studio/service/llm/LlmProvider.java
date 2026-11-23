package com.aidjstudio.ai_dj_studio.service.llm;

import com.aidjstudio.ai_dj_studio.dto.track.RetrievedMetadataEntry;
import com.aidjstudio.ai_dj_studio.entity.Track;
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

    /**
     * Generates a grounded transition announcement / DJ banter explaining the mix from currentTrack to nextTrack,
     * strictly grounded in the provided authored metadata facts and musical transition context.
     *
     * @param currentTrack     the track currently playing
     * @param nextTrack        the incoming selected track
     * @param matchExplanation deterministic acoustic match / ranking explanation
     * @param evidence         authored metadata timeline events for current and incoming tracks
     * @return Mono emitting the grounded transition script
     */
    Mono<String> generateTransitionScript(
            Track currentTrack,
            Track nextTrack,
            String matchExplanation,
            List<RetrievedMetadataEntry> evidence
    );
}
