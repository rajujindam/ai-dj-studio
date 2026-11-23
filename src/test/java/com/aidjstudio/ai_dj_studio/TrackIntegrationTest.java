package com.aidjstudio.ai_dj_studio;

import com.aidjstudio.ai_dj_studio.dto.track.TrackQnaRequest;
import com.aidjstudio.ai_dj_studio.dto.track.TrackQnaResponse;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.entity.TrackTimelineEvent;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackTimelineEventRepository;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class TrackIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private TrackTimelineEventRepository timelineEventRepository;

    @Autowired
    private JwtEncoder jwtEncoder;

    private Track testTrack;
    private final List<Long> createdTrackIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        Track track = new Track(
                null,
                "Integration Test Track",
                "Integration Artist",
                180,
                "https://cdn.aidj.com/audio/test-track.mp3",
                new BigDecimal("128.00"),
                "11B",
                new BigDecimal("0.820"),
                new BigDecimal("0.750")
        );
        testTrack = trackRepository.save(track).block();
        assertNotNull(testTrack);
        assertNotNull(testTrack.getId());
        createdTrackIds.add(testTrack.getId());
    }

    @AfterEach
    void tearDown() {
        for (Long trackId : createdTrackIds) {
            timelineEventRepository.findByTrackIdOrderByTsSecondsAsc(trackId)
                    .flatMap(event -> timelineEventRepository.deleteById(event.getId()))
                    .blockLast();
            trackRepository.deleteById(trackId).block();
        }
        createdTrackIds.clear();
    }

    private String generateValidToken() {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ai-dj-studio")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .subject("100")
                .claim("email", "listener@example.com")
                .claim("role", "LISTENER")
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        return jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();
    }

    @Test
    void getAllTracks_unauthenticated_shouldReturnUnauthorized() {
        webTestClient.get()
                .uri("/api/v1/tracks")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void getTrackById_unauthenticated_shouldReturnUnauthorized() {
        webTestClient.get()
                .uri("/api/v1/tracks/" + testTrack.getId())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void getAcousticProfile_unauthenticated_shouldReturnUnauthorized() {
        webTestClient.get()
                .uri("/api/v1/tracks/" + testTrack.getId() + "/acoustic-profile")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void getAllTracks_authenticated_shouldReturnTracksList() {
        String token = generateValidToken();
        String titlePath = String.format("$[?(@.id == %d)].title", testTrack.getId());
        String artistPath = String.format("$[?(@.id == %d)].artist", testTrack.getId());
        String keyPath = String.format("$[?(@.id == %d)].camelotKey", testTrack.getId());

        webTestClient.get()
                .uri("/api/v1/tracks")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray()
                .jsonPath(titlePath).isEqualTo("Integration Test Track")
                .jsonPath(artistPath).isEqualTo("Integration Artist")
                .jsonPath(keyPath).isEqualTo("11B");
    }

    @Test
    void getTrackById_authenticated_whenFound_shouldReturnTrack() {
        String token = generateValidToken();

        webTestClient.get()
                .uri("/api/v1/tracks/" + testTrack.getId())
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(testTrack.getId())
                .jsonPath("$.title").isEqualTo("Integration Test Track")
                .jsonPath("$.artist").isEqualTo("Integration Artist")
                .jsonPath("$.durationSeconds").isEqualTo(180)
                .jsonPath("$.audioAssetUrl").isEqualTo("https://cdn.aidj.com/audio/test-track.mp3")
                .jsonPath("$.bpm").isEqualTo(128.00)
                .jsonPath("$.camelotKey").isEqualTo("11B")
                .jsonPath("$.energy").isEqualTo(0.820)
                .jsonPath("$.valence").isEqualTo(0.750);
    }

    @Test
    void getTrackById_authenticated_whenNotFound_shouldReturnNotFound() {
        String token = generateValidToken();
        long nonExistentId = 999999L;

        webTestClient.get()
                .uri("/api/v1/tracks/" + nonExistentId)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.message").isEqualTo("Track not found with id: " + nonExistentId);
    }

    @Test
    void getAcousticProfile_authenticated_whenFound_shouldReturnProfileWithOrderedTimeline() {
        String token = generateValidToken();

        // Insert events in reverse order (ts = 90 before ts = 12) to test ascending ordering
        TrackTimelineEvent eventLater = TrackTimelineEvent.builder()
                .trackId(testTrack.getId())
                .tsSeconds(90)
                .eventType("trivia")
                .payload(Json.of("{\"text\":\"Produced during an all-night session in Berlin.\"}"))
                .build();
        timelineEventRepository.save(eventLater).block();

        TrackTimelineEvent eventEarlier = TrackTimelineEvent.builder()
                .trackId(testTrack.getId())
                .tsSeconds(12)
                .eventType("sample_credit")
                .payload(Json.of("{\"text\":\"Vocal chop sampled from a 1981 soul record.\"}"))
                .build();
        timelineEventRepository.save(eventEarlier).block();

        webTestClient.get()
                .uri("/api/v1/tracks/" + testTrack.getId() + "/acoustic-profile")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.trackId").isEqualTo(testTrack.getId())
                .jsonPath("$.title").isEqualTo("Integration Test Track")
                .jsonPath("$.artist").isEqualTo("Integration Artist")
                .jsonPath("$.acoustic.bpm").isEqualTo(128.00)
                .jsonPath("$.acoustic.camelotKey").isEqualTo("11B")
                .jsonPath("$.acoustic.energy").isEqualTo(0.820)
                .jsonPath("$.acoustic.valence").isEqualTo(0.750)
                .jsonPath("$.timeline").isArray()
                .jsonPath("$.timeline.length()").isEqualTo(2)
                .jsonPath("$.timeline[0].ts").isEqualTo(12)
                .jsonPath("$.timeline[0].type").isEqualTo("sample_credit")
                .jsonPath("$.timeline[0].text").isEqualTo("Vocal chop sampled from a 1981 soul record.")
                .jsonPath("$.timeline[1].ts").isEqualTo(90)
                .jsonPath("$.timeline[1].type").isEqualTo("trivia")
                .jsonPath("$.timeline[1].text").isEqualTo("Produced during an all-night session in Berlin.");
    }

    @Test
    void getAcousticProfile_authenticated_whenNotFound_shouldReturnNotFound() {
        String token = generateValidToken();
        long nonExistentId = 999999L;

        webTestClient.get()
                .uri("/api/v1/tracks/" + nonExistentId + "/acoustic-profile")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.message").isEqualTo("Track not found with id: " + nonExistentId);
    }

    @Test
    void getAcousticProfile_authenticated_whenAcousticDataMissingOrInvalid_shouldReturnNotFound() {
        String token = generateValidToken();

        Track invalidTrack = new Track(
                null,
                "Incomplete Track",
                "Some Artist",
                200,
                "https://cdn.aidj.com/audio/incomplete.mp3",
                null, // Missing BPM
                "99Z", // Invalid Camelot key fitting in VARCHAR(5)
                null,
                null
        );
        Track savedInvalidTrack = trackRepository.save(invalidTrack).block();
        assertNotNull(savedInvalidTrack);
        createdTrackIds.add(savedInvalidTrack.getId());

        webTestClient.get()
                .uri("/api/v1/tracks/" + savedInvalidTrack.getId() + "/acoustic-profile")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.message").isEqualTo("Track acoustic profile not found or incomplete for id: " + savedInvalidTrack.getId());
    }

    @Test
    void searchTimelineMetadata_unauthenticated_shouldReturnUnauthorized() {
        webTestClient.get()
                .uri("/api/v1/tracks/" + testTrack.getId() + "/timeline/search?query=soul")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void searchTimelineMetadata_authenticated_withQuery_shouldReturnMatchingEntriesAndStableSourceIds() {
        String token = generateValidToken();

        TrackTimelineEvent event = TrackTimelineEvent.builder()
                .trackId(testTrack.getId())
                .tsSeconds(15)
                .eventType("sample_credit")
                .payload(Json.of("{\"text\":\"Sampled from a 1974 soul record.\"}"))
                .build();
        TrackTimelineEvent savedEvent = timelineEventRepository.save(event).block();
        assertNotNull(savedEvent);

        webTestClient.get()
                .uri("/api/v1/tracks/" + testTrack.getId() + "/timeline/search?query=soul")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].id").isEqualTo(savedEvent.getId())
                .jsonPath("$[0].trackId").isEqualTo(testTrack.getId())
                .jsonPath("$[0].sourceId").isEqualTo("track:" + testTrack.getId() + ":timeline:" + savedEvent.getId())
                .jsonPath("$[0].eventType").isEqualTo("sample_credit")
                .jsonPath("$[0].tsSeconds").isEqualTo(15)
                .jsonPath("$[0].text").isEqualTo("Sampled from a 1974 soul record.")
                .jsonPath("$[0].score").isNumber();
    }

    @Test
    void searchTimelineMetadata_authenticated_whenNoMatches_shouldReturnEmptyList() {
        String token = generateValidToken();

        TrackTimelineEvent event = TrackTimelineEvent.builder()
                .trackId(testTrack.getId())
                .tsSeconds(15)
                .eventType("sample_credit")
                .payload(Json.of("{\"text\":\"Sampled from a 1974 soul record.\"}"))
                .build();
        timelineEventRepository.save(event).block();

        webTestClient.get()
                .uri("/api/v1/tracks/" + testTrack.getId() + "/timeline/search?query=harp")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray()
                .jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void searchTimelineMetadata_authenticated_trackIsolation_shouldNeverReturnOtherTrackEvents() {
        String token = generateValidToken();

        // Event for testTrack
        TrackTimelineEvent event1 = TrackTimelineEvent.builder()
                .trackId(testTrack.getId())
                .tsSeconds(20)
                .eventType("trivia")
                .payload(Json.of("{\"text\":\"Recorded in London studio.\"}"))
                .build();
        timelineEventRepository.save(event1).block();

        // Create second track
        Track otherTrack = new Track(
                null,
                "Other Isolated Track",
                "Other Artist",
                210,
                "https://cdn.aidj.com/audio/other.mp3",
                new BigDecimal("130.00"),
                "8A",
                new BigDecimal("0.700"),
                new BigDecimal("0.600")
        );
        Track savedOtherTrack = trackRepository.save(otherTrack).block();
        assertNotNull(savedOtherTrack);
        createdTrackIds.add(savedOtherTrack.getId());

        // Event for second track with matching word "London"
        TrackTimelineEvent event2 = TrackTimelineEvent.builder()
                .trackId(savedOtherTrack.getId())
                .tsSeconds(40)
                .eventType("trivia")
                .payload(Json.of("{\"text\":\"London orchestra collaboration.\"}"))
                .build();
        timelineEventRepository.save(event2).block();

        // Search testTrack for "London" - must ONLY return event1, never event2
        webTestClient.get()
                .uri("/api/v1/tracks/" + testTrack.getId() + "/timeline/search?query=London")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$").isArray()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].trackId").isEqualTo(testTrack.getId())
                .jsonPath("$[0].text").isEqualTo("Recorded in London studio.");
    }

    @Test
    void askTrackQuestion_unauthenticated_shouldReturnUnauthorized() {
        webTestClient.post()
                .uri("/api/v1/tracks/" + testTrack.getId() + "/qna")
                .bodyValue(new TrackQnaRequest("What sample was used?"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void askTrackQuestion_authenticated_grounded_shouldReturnAnswerAndSources() {
        String token = generateValidToken();

        TrackTimelineEvent event = TrackTimelineEvent.builder()
                .trackId(testTrack.getId())
                .tsSeconds(15)
                .eventType("sample_credit")
                .payload(Json.of("{\"text\":\"Sampled from a 1974 soul record.\"}"))
                .build();
        TrackTimelineEvent savedEvent = timelineEventRepository.save(event).block();
        assertNotNull(savedEvent);

        webTestClient.post()
                .uri("/api/v1/tracks/" + testTrack.getId() + "/qna")
                .headers(headers -> headers.setBearerAuth(token))
                .bodyValue(new TrackQnaRequest("What soul sample is used?"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(TrackQnaResponse.class)
                .value(response -> {
                    assertTrue(response.isGrounded());
                    assertEquals(1, response.getSourceIds().size());
                    assertEquals("track:" + testTrack.getId() + ":timeline:" + savedEvent.getId(), response.getSourceIds().get(0));
                    assertEquals(1, response.getSources().size());
                    assertEquals("Sampled from a 1974 soul record.", response.getSources().get(0).getText());
                    assertTrue(response.getAnswer().contains("1974 soul record"));
                });
    }

    @Test
    void askTrackQuestion_authenticated_noEvidence_shouldReturnCannotAnswer() {
        String token = generateValidToken();

        webTestClient.post()
                .uri("/api/v1/tracks/" + testTrack.getId() + "/qna")
                .headers(headers -> headers.setBearerAuth(token))
                .bodyValue(new TrackQnaRequest("Who is playing the saxophone?"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.grounded").isEqualTo(false)
                .jsonPath("$.answer").isEqualTo("Information not present in available metadata for this track.")
                .jsonPath("$.sourceIds.length()").isEqualTo(0)
                .jsonPath("$.sources.length()").isEqualTo(0);
    }

    @Test
    void askTrackQuestion_authenticated_blankQuestion_shouldReturnBadRequest() {
        String token = generateValidToken();

        webTestClient.post()
                .uri("/api/v1/tracks/" + testTrack.getId() + "/qna")
                .headers(headers -> headers.setBearerAuth(token))
                .bodyValue(new TrackQnaRequest("   "))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.message").isNotEmpty();
    }
}
