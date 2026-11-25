package com.aidjstudio.ai_dj_studio.dto.announcement;

import com.aidjstudio.ai_dj_studio.dto.track.RetrievedMetadataEntry;
import com.aidjstudio.ai_dj_studio.dto.websocket.WsEventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnnouncementPayloadContractTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Payload Contract: Transition announcement serialize and deserialize through WsEventEnvelope")
    void testTransitionAnnouncementPayloadContract() throws Exception {
        Instant now = Instant.parse("2026-11-25T12:00:00Z");

        RetrievedMetadataEntry entry1 = RetrievedMetadataEntry.builder()
                .sourceId("track:1:timeline:101")
                .trackId(1L)
                .eventType("DROP")
                .tsSeconds(64)
                .text("Massive synth crescendo into the main hook")
                .build();

        RetrievedMetadataEntry entry2 = RetrievedMetadataEntry.builder()
                .sourceId("track:2:timeline:201")
                .trackId(2L)
                .eventType("INTRO")
                .tsSeconds(0)
                .text("Pulsing bass groove intro at 128 BPM")
                .build();

        TransitionAnnouncementPayload payload = TransitionAnnouncementPayload.builder()
                .announcementType("TRANSITION")
                .loungeId(10L)
                .currentTrackId(1L)
                .currentTrackTitle("Midnight City")
                .currentTrackArtist("M83")
                .nextTrackId(2L)
                .nextTrackTitle("Starboy")
                .nextTrackArtist("The Weeknd")
                .bpmDelta(new BigDecimal("0.00"))
                .camelotRelation("SAME_KEY")
                .rankingExplanation("Candidate passed harmonic and tempo compatibility.")
                .script("Mixing from \"Midnight City\" by M83 into \"Starboy\" by The Weeknd. [track:1:timeline:101]")
                .sourceIds(List.of("track:1:timeline:101", "track:2:timeline:201"))
                .sources(List.of(entry1, entry2))
                .createdAt(now)
                .build();

        WsEventEnvelope<TransitionAnnouncementPayload> envelope = WsEventEnvelope.<TransitionAnnouncementPayload>builder()
                .event("announcement.transition")
                .loungeId(10L)
                .payload(payload)
                .ts(now)
                .build();

        String json = objectMapper.writeValueAsString(envelope);

        // Verify JSON contents
        assertThat(json).contains("\"event\":\"announcement.transition\"");
        assertThat(json).contains("\"loungeId\":10");
        assertThat(json).contains("\"announcementType\":\"TRANSITION\"");
        assertThat(json).contains("\"currentTrackId\":1");
        assertThat(json).contains("\"currentTrackTitle\":\"Midnight City\"");
        assertThat(json).contains("\"currentTrackArtist\":\"M83\"");
        assertThat(json).contains("\"nextTrackId\":2");
        assertThat(json).contains("\"nextTrackTitle\":\"Starboy\"");
        assertThat(json).contains("\"nextTrackArtist\":\"The Weeknd\"");
        assertThat(json).contains("\"bpmDelta\":0.00");
        assertThat(json).contains("\"camelotRelation\":\"SAME_KEY\"");
        assertThat(json).contains("\"rankingExplanation\":\"Candidate passed harmonic and tempo compatibility.\"");
        assertThat(json).contains("\"sourceIds\":[\"track:1:timeline:101\",\"track:2:timeline:201\"]");
        assertThat(json).contains("\"track:1:timeline:101\"");
        assertThat(json).contains("\"Massive synth crescendo into the main hook\"");

        // Deserialization round-trip
        WsEventEnvelope<TransitionAnnouncementPayload> deserialized = objectMapper.readValue(
                json,
                new TypeReference<WsEventEnvelope<TransitionAnnouncementPayload>>() {}
        );

        assertThat(deserialized.getEvent()).isEqualTo("announcement.transition");
        assertThat(deserialized.getLoungeId()).isEqualTo(10L);
        assertThat(deserialized.getPayload().getAnnouncementType()).isEqualTo("TRANSITION");
        assertThat(deserialized.getPayload().getCurrentTrackTitle()).isEqualTo("Midnight City");
        assertThat(deserialized.getPayload().getNextTrackTitle()).isEqualTo("Starboy");
        assertThat(deserialized.getPayload().getScript()).contains("Mixing from \"Midnight City\"");
        assertThat(deserialized.getPayload().getSourceIds()).containsExactly("track:1:timeline:101", "track:2:timeline:201");
        assertThat(deserialized.getPayload().getSources()).hasSize(2);
        assertThat(deserialized.getPayload().getSources().get(0).getEventType()).isEqualTo("DROP");
    }

    @Test
    @DisplayName("Payload Contract: Soundclash announcement serialize and deserialize through WsEventEnvelope")
    void testSoundclashAnnouncementPayloadContract() throws Exception {
        Instant now = Instant.parse("2026-11-25T12:05:00Z");

        RetrievedMetadataEntry entry = RetrievedMetadataEntry.builder()
                .sourceId("track:5:timeline:501")
                .trackId(5L)
                .eventType("TRIVIA")
                .tsSeconds(45)
                .text("Co-written and produced by Daft Punk")
                .build();

        SoundclashCandidateSummary cand1 = SoundclashCandidateSummary.builder()
                .candidateId(101L)
                .trackId(5L)
                .trackTitle("Starboy")
                .trackArtist("The Weeknd")
                .voteCount(14)
                .build();

        SoundclashCandidateSummary cand2 = SoundclashCandidateSummary.builder()
                .candidateId(102L)
                .trackId(6L)
                .trackTitle("Strobe")
                .trackArtist("deadmau5")
                .voteCount(8)
                .build();

        SoundclashAnnouncementPayload payload = SoundclashAnnouncementPayload.builder()
                .announcementType("SOUNDCLASH")
                .loungeId(10L)
                .battleId(50L)
                .winningTrackId(5L)
                .winningTrackTitle("Starboy")
                .winningTrackArtist("The Weeknd")
                .winningCandidateId(101L)
                .winnerVoteCount(14)
                .totalVotes(22)
                .candidates(List.of(cand1, cand2))
                .script("Soundclash winner decided! \"Starboy\" by The Weeknd won the clash with 14 of 22 votes! [track:5:timeline:501]: Co-written and produced by Daft Punk")
                .sourceIds(List.of("track:5:timeline:501"))
                .sources(List.of(entry))
                .createdAt(now)
                .build();

        WsEventEnvelope<SoundclashAnnouncementPayload> envelope = WsEventEnvelope.<SoundclashAnnouncementPayload>builder()
                .event("announcement.soundclash")
                .loungeId(10L)
                .payload(payload)
                .ts(now)
                .build();

        String json = objectMapper.writeValueAsString(envelope);

        // Verify JSON contents
        assertThat(json).contains("\"event\":\"announcement.soundclash\"");
        assertThat(json).contains("\"loungeId\":10");
        assertThat(json).contains("\"announcementType\":\"SOUNDCLASH\"");
        assertThat(json).contains("\"battleId\":50");
        assertThat(json).contains("\"winningTrackId\":5");
        assertThat(json).contains("\"winningTrackTitle\":\"Starboy\"");
        assertThat(json).contains("\"winningCandidateId\":101");
        assertThat(json).contains("\"winnerVoteCount\":14");
        assertThat(json).contains("\"totalVotes\":22");
        assertThat(json).contains("\"candidates\":[");
        assertThat(json).contains("\"trackTitle\":\"Starboy\"");
        assertThat(json).contains("\"trackTitle\":\"Strobe\"");
        assertThat(json).contains("\"sourceIds\":[\"track:5:timeline:501\"]");
        assertThat(json).contains("\"Co-written and produced by Daft Punk\"");

        // Deserialization round-trip
        WsEventEnvelope<SoundclashAnnouncementPayload> deserialized = objectMapper.readValue(
                json,
                new TypeReference<WsEventEnvelope<SoundclashAnnouncementPayload>>() {}
        );

        assertThat(deserialized.getEvent()).isEqualTo("announcement.soundclash");
        assertThat(deserialized.getLoungeId()).isEqualTo(10L);
        assertThat(deserialized.getPayload().getAnnouncementType()).isEqualTo("SOUNDCLASH");
        assertThat(deserialized.getPayload().getBattleId()).isEqualTo(50L);
        assertThat(deserialized.getPayload().getWinningTrackTitle()).isEqualTo("Starboy");
        assertThat(deserialized.getPayload().getWinnerVoteCount()).isEqualTo(14);
        assertThat(deserialized.getPayload().getTotalVotes()).isEqualTo(22);
        assertThat(deserialized.getPayload().getCandidates()).hasSize(2);
        assertThat(deserialized.getPayload().getSourceIds()).containsExactly("track:5:timeline:501");
        assertThat(deserialized.getPayload().getSources().get(0).getText()).isEqualTo("Co-written and produced by Daft Punk");
    }
}
