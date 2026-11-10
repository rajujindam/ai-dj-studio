package com.aidjstudio.ai_dj_studio;

import com.aidjstudio.ai_dj_studio.dto.lounge.CreateLoungeRequest;
import com.aidjstudio.ai_dj_studio.dto.lounge.CreateLoungeResponse;
import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import com.aidjstudio.ai_dj_studio.entity.Track;
import com.aidjstudio.ai_dj_studio.entity.User;
import com.aidjstudio.ai_dj_studio.repository.DjLoungeRepository;
import com.aidjstudio.ai_dj_studio.repository.TrackRepository;
import com.aidjstudio.ai_dj_studio.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class LoungeIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TrackRepository trackRepository;

    @Autowired
    private DjLoungeRepository djLoungeRepository;

    @Autowired
    private JwtEncoder jwtEncoder;

    private User testHostUser;
    private Track testTrack;
    private final List<Long> createdLoungeIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        User user = new User(
                null,
                "host+" + UUID.randomUUID() + "@example.com",
                "test_password_hash",
                "Integration Host",
                "HOST"
        );
        testHostUser = userRepository.save(user).block();
        assertNotNull(testHostUser);
        assertNotNull(testHostUser.getId());

        Track track = new Track(
                null,
                "Lounge Seed Track",
                "Seed Artist",
                210,
                "https://cdn.aidj.com/audio/seed.mp3",
                new BigDecimal("126.00"),
                "10A",
                new BigDecimal("0.800"),
                new BigDecimal("0.700")
        );
        testTrack = trackRepository.save(track).block();
        assertNotNull(testTrack);
        assertNotNull(testTrack.getId());
    }

    @AfterEach
    void tearDown() {
        for (Long loungeId : createdLoungeIds) {
            djLoungeRepository.deleteById(loungeId).block();
        }
        createdLoungeIds.clear();

        if (testTrack != null && testTrack.getId() != null) {
            trackRepository.deleteById(testTrack.getId()).block();
        }

        if (testHostUser != null && testHostUser.getId() != null) {
            userRepository.deleteById(testHostUser.getId()).block();
        }
    }

    private String generateValidToken(Long userId, String email, String role) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ai-dj-studio")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("role", role)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        return jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();
    }

    @Test
    void createLounge_unauthenticated_shouldReturnUnauthorized() {
        CreateLoungeRequest request = CreateLoungeRequest.builder()
                .seedTrackId(testTrack.getId())
                .vibeProfile("High-Energy EDM")
                .maxParticipants(40)
                .bpmTolerancePct(new BigDecimal("8.00"))
                .soundclashEnabled(true)
                .aiVerbosity("normal")
                .build();

        webTestClient.post()
                .uri("/api/v1/lounges")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void createLounge_authenticated_withValidData_shouldCreateLoungeAndEstablishHost() {
        String hostToken = generateValidToken(testHostUser.getId(), testHostUser.getEmail(), testHostUser.getRole());

        CreateLoungeRequest request = CreateLoungeRequest.builder()
                .seedTrackId(testTrack.getId())
                .vibeProfile("High-Energy EDM")
                .maxParticipants(40)
                .bpmTolerancePct(new BigDecimal("8.00"))
                .soundclashEnabled(true)
                .aiVerbosity("normal")
                .build();

        CreateLoungeResponse response = webTestClient.post()
                .uri("/api/v1/lounges")
                .headers(headers -> headers.setBearerAuth(hostToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateLoungeResponse.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(response);
        assertNotNull(response.getLoungeId());
        createdLoungeIds.add(response.getLoungeId());

        assertNotNull(response.getInviteCode());
        assertTrue(response.getInviteCode().startsWith("SP-"));
        assertEquals(LoungeStatus.SCHEDULED, response.getStatus());
        assertEquals(testHostUser.getId(), response.getHostUserId());
        assertNotNull(response.getCreatedAt());

        Long loungeId = response.getLoungeId();

        // Host authority verification: host can start the lounge
        webTestClient.post()
                .uri("/api/v1/lounges/" + loungeId + "/start")
                .headers(headers -> headers.setBearerAuth(hostToken))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("LIVE");

        // Host membership verification: host does not need participant membership
        webTestClient.post()
                .uri("/api/v1/lounges/" + loungeId + "/join")
                .headers(headers -> headers.setBearerAuth(hostToken))
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.message").isEqualTo("The lounge host does not need participant membership");
    }

    @Test
    void createLounge_whenSeedTrackNotFound_shouldReturnNotFound() {
        String hostToken = generateValidToken(testHostUser.getId(), testHostUser.getEmail(), testHostUser.getRole());

        CreateLoungeRequest request = CreateLoungeRequest.builder()
                .seedTrackId(999999L)
                .vibeProfile("Deep House")
                .build();

        webTestClient.post()
                .uri("/api/v1/lounges")
                .headers(headers -> headers.setBearerAuth(hostToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.status").isEqualTo(404)
                .jsonPath("$.message").isEqualTo("Seed track not found with id: 999999");
    }

    @Test
    void createLounge_whenSeedTrackIdMissing_shouldReturnBadRequest() {
        String hostToken = generateValidToken(testHostUser.getId(), testHostUser.getEmail(), testHostUser.getRole());

        // seedTrackId is null
        CreateLoungeRequest request = CreateLoungeRequest.builder()
                .vibeProfile("Techno")
                .build();

        webTestClient.post()
                .uri("/api/v1/lounges")
                .headers(headers -> headers.setBearerAuth(hostToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isBadRequest();
    }
}
