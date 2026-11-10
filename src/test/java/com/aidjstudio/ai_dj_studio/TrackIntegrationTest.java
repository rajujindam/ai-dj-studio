package com.aidjstudio.ai_dj_studio;

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

import static org.junit.jupiter.api.Assertions.assertNotNull;

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
}
