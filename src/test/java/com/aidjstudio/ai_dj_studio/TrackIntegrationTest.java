package com.aidjstudio.ai_dj_studio;

import com.aidjstudio.ai_dj_studio.dto.track.TrackQnaRequest;
import com.aidjstudio.ai_dj_studio.dto.track.TrackQnaResponse;
import com.aidjstudio.ai_dj_studio.dto.transition.TransitionDecisionRequest;
import com.aidjstudio.ai_dj_studio.dto.transition.TransitionDecisionResponse;
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
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                .jsonPath("$.bpm").isEqualTo(128.0)
                .jsonPath("$.camelotKey").isEqualTo("11B")
                .jsonPath("$.energy").isEqualTo(0.82)
                .jsonPath("$.valence").isEqualTo(0.75);
    }

    @Test
    void getTrackById_authenticated_whenNotFound_shouldReturnNotFound() {
        String token = generateValidToken();

        webTestClient.get()
                .uri("/api/v1/tracks/999999")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.message").isEqualTo("Track not found with id: 999999");
    }

    @Test
    void getAcousticProfile_authenticated_whenFound_shouldReturnAcousticProfile() {
        String token = generateValidToken();

        webTestClient.get()
                .uri("/api/v1/tracks/" + testTrack.getId() + "/acoustic-profile")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.trackId").isEqualTo(testTrack.getId())
                .jsonPath("$.title").isEqualTo("Integration Test Track")
                .jsonPath("$.artist").isEqualTo("Integration Artist")
                .jsonPath("$.acoustic.bpm").isEqualTo(128.0)
                .jsonPath("$.acoustic.camelotKey").isEqualTo("11B")
                .jsonPath("$.acoustic.energy").isEqualTo(0.82)
                .jsonPath("$.acoustic.valence").isEqualTo(0.75);
    }

    @Test
    void getAcousticProfile_authenticated_whenNotFound_shouldReturnNotFound() {
        String token = generateValidToken();

        webTestClient.get()
                .uri("/api/v1/tracks/999999/acoustic-profile")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.message").isEqualTo("Track not found with id: 999999");
    }

    @Test
    void searchTimelineMetadata_unauthenticated_shouldReturnUnauthorized() {
        webTestClient.get()
                .uri("/api/v1/tracks/" + testTrack.getId() + "/timeline/search")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void searchTimelineMetadata_authenticated_shouldReturnMatchingEntries() {
        String token = generateValidToken();

        TrackTimelineEvent event1 = TrackTimelineEvent.builder()
                .trackId(testTrack.getId())
                .tsSeconds(32)
                .eventType("drop")
                .payload(Json.of("{\"text\":\"Main bassline drops with heavy groove\"}"))
                .build();
        TrackTimelineEvent event2 = TrackTimelineEvent.builder()
                .trackId(testTrack.getId())
                .tsSeconds(64)
                .eventType("vocal")
                .payload(Json.of("{\"text\":\"Lead female vocal enters melodic breakdown\"}"))
                .build();
        timelineEventRepository.save(event1).block();
        timelineEventRepository.save(event2).block();

        webTestClient.get()
                .uri("/api/v1/tracks/" + testTrack.getId() + "/timeline/search?query=bassline")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].eventType").isEqualTo("drop")
                .jsonPath("$[0].text").isEqualTo("Main bassline drops with heavy groove")
                .jsonPath("$[0].sourceId").isNotEmpty();
    }

    @Test
    void searchTimelineMetadata_withEventTypeFilter_shouldFilterCorrectly() {
        String token = generateValidToken();

        TrackTimelineEvent event1 = TrackTimelineEvent.builder()
                .trackId(testTrack.getId())
                .tsSeconds(10)
                .eventType("intro")
                .payload(Json.of("{\"text\":\"Hi-hat rhythm build up\"}"))
                .build();
        TrackTimelineEvent event2 = TrackTimelineEvent.builder()
                .trackId(testTrack.getId())
                .tsSeconds(40)
                .eventType("drop")
                .payload(Json.of("{\"text\":\"Synth lead melody explosion\"}"))
                .build();
        timelineEventRepository.save(event1).block();
        timelineEventRepository.save(event2).block();

        webTestClient.get()
                .uri("/api/v1/tracks/" + testTrack.getId() + "/timeline/search?eventType=drop")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].eventType").isEqualTo("drop")
                .jsonPath("$[0].text").isEqualTo("Synth lead melody explosion");
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

    // ==========================================
    // F21: TRANSITION DECISION INTEGRATION TESTS
    // ==========================================

    @Test
    void decideTransition_unauthenticated_shouldReturnUnauthorized() {
        webTestClient.post()
                .uri("/api/v1/tracks/" + testTrack.getId() + "/transition-decision")
                .bodyValue(TransitionDecisionRequest.builder()
                        .candidateTrackIds(List.of(999L))
                        .build())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void decideTransition_authenticated_validCandidate_shouldReturnGroundedSegue() {
        String token = generateValidToken();

        // Create a compatible candidate track: testTrack is 128 BPM, Camelot 11B
        // Candidate: 126 BPM, Camelot 10B (adjacent key, within ±8% BPM)
        Track candidate = new Track(
                null,
                "Compatible Candidate",
                "Compatible Artist",
                200,
                "https://cdn.aidj.com/audio/cand.mp3",
                new BigDecimal("126.00"),
                "10B",
                new BigDecimal("0.850"),
                new BigDecimal("0.700")
        );
        Track savedCandidate = trackRepository.save(candidate).block();
        assertNotNull(savedCandidate);
        createdTrackIds.add(savedCandidate.getId());

        // Add timeline metadata for candidate
        TrackTimelineEvent event = TrackTimelineEvent.builder()
                .trackId(savedCandidate.getId())
                .tsSeconds(0)
                .eventType("intro")
                .payload(Json.of("{\"text\":\"Warm analog synth chord pads with rolling kick drum.\"}"))
                .build();
        TrackTimelineEvent savedEvent = timelineEventRepository.save(event).block();
        assertNotNull(savedEvent);

        TransitionDecisionRequest request = TransitionDecisionRequest.builder()
                .candidateTrackIds(List.of(savedCandidate.getId()))
                .targetEnergy(new BigDecimal("0.85"))
                .build();

        webTestClient.post()
                .uri("/api/v1/tracks/" + testTrack.getId() + "/transition-decision")
                .headers(headers -> headers.setBearerAuth(token))
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TransitionDecisionResponse.class)
                .value(response -> {
                    assertTrue(response.isSeguePossible());
                    assertEquals(testTrack.getId(), response.getCurrentTrackId());
                    assertEquals(savedCandidate.getId(), response.getSelectedTrackId());
                    assertEquals("Compatible Candidate", response.getSelectedTrackTitle());
                    assertEquals("ADJACENT_KEY", response.getCamelotRelation());
                    assertEquals(new BigDecimal("-2.00"), response.getBpmDelta());
                    assertTrue(response.getSourceIds().contains("track:" + savedCandidate.getId() + ":timeline:" + savedEvent.getId()));
                    assertTrue(response.getScript().contains("Compatible Candidate"));
                    assertTrue(response.getScript().contains("track:" + savedCandidate.getId() + ":timeline:" + savedEvent.getId()));
                });
    }

    @Test
    void decideTransition_authenticated_incompatibleCandidate_shouldReturnSafeNoSegue() {
        String token = generateValidToken();

        // Incompatible candidate: 160 BPM, 3A (BPM & Camelot clash against 128 BPM, 11B)
        Track incompatible = new Track(
                null,
                "Incompatible Candidate",
                "Clashing Artist",
                190,
                "https://cdn.aidj.com/audio/incomp.mp3",
                new BigDecimal("160.00"),
                "3A",
                new BigDecimal("0.900"),
                new BigDecimal("0.500")
        );
        Track savedIncompatible = trackRepository.save(incompatible).block();
        assertNotNull(savedIncompatible);
        createdTrackIds.add(savedIncompatible.getId());

        TransitionDecisionRequest request = TransitionDecisionRequest.builder()
                .candidateTrackIds(List.of(savedIncompatible.getId()))
                .build();

        webTestClient.post()
                .uri("/api/v1/tracks/" + testTrack.getId() + "/transition-decision")
                .headers(headers -> headers.setBearerAuth(token))
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TransitionDecisionResponse.class)
                .value(response -> {
                    assertFalse(response.isSeguePossible());
                    assertEquals(testTrack.getId(), response.getCurrentTrackId());
                    assertNull(response.getSelectedTrackId());
                    assertNull(response.getSelectedTrackTitle());
                    assertNull(response.getBpmDelta());
                    assertNull(response.getCamelotRelation());
                    assertTrue(response.getRankingExplanation().contains("No candidate track met harmonic and BPM segue constraints"));
                    assertTrue(response.getSourceIds().isEmpty());
                });
    }

    @Test
    void decideTransition_authenticated_emptyCandidates_shouldReturnBadRequest() {
        String token = generateValidToken();

        TransitionDecisionRequest request = TransitionDecisionRequest.builder()
                .candidateTrackIds(Collections.emptyList())
                .build();

        webTestClient.post()
                .uri("/api/v1/tracks/" + testTrack.getId() + "/transition-decision")
                .headers(headers -> headers.setBearerAuth(token))
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.message").isNotEmpty();
    }
}
