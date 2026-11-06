
package com.aidjstudio.ai_dj_studio;

import com.aidjstudio.ai_dj_studio.dto.auth.AuthResponse;
import com.aidjstudio.ai_dj_studio.dto.auth.LoginRequest;
import com.aidjstudio.ai_dj_studio.dto.auth.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
class SecurityIntegrationTests {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Test
    void requestWithoutTokenShouldReturnUnauthorized() {
        webTestClient.get()
                .uri("/api/v1/users/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void requestWithMalformedTokenShouldReturnUnauthorized() {
        webTestClient.get()
                .uri("/api/v1/users/me")
                .headers(headers -> headers.setBearerAuth("not-a-valid-jwt"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void validTokenShouldReturnAuthenticatedUserProfile() {
        AuthResponse user = registerAndLogin();

        assertNotNull(user.getAccessToken());

        webTestClient.get()
                .uri("/api/v1/users/me")
                .headers(headers ->
                        headers.setBearerAuth(user.getAccessToken()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(user.getId())
                .jsonPath("$.email").isEqualTo(user.getEmail())
                .jsonPath("$.displayName").isEqualTo(user.getDisplayName())
                .jsonPath("$.role").isEqualTo("LISTENER");
    }

    @Test
    void listenerShouldNotAccessAdminEndpoint() {
        AuthResponse user = registerAndLogin();

        webTestClient.get()
                .uri("/api/v1/admin/test")
                .headers(headers ->
                        headers.setBearerAuth(user.getAccessToken()))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void expiredTokenShouldReturnUnauthorized() {
        String expiredToken = generateExpiredToken();

        webTestClient.get()
                .uri("/api/v1/users/me")
                .headers(headers -> headers.setBearerAuth(expiredToken))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    private AuthResponse registerAndLogin() {
        String email = "security-test-" + UUID.randomUUID() + "@example.com";
        String password = "StrongPassword123";

        RegisterRequest registerRequest = new RegisterRequest();
        registerRequest.setEmail(email);
        registerRequest.setPassword(password);
        registerRequest.setDisplayName("Security Test User");

        webTestClient.post()
                .uri("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(registerRequest)
                .exchange()
                .expectStatus().is2xxSuccessful();

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setEmail(email);
        loginRequest.setPassword(password);

        AuthResponse response = webTestClient.post()
                .uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(loginRequest)
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(response);
        return response;
    }

    private String generateExpiredToken() {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ai-dj-studio")
                .issuedAt(now.minusSeconds(7200))
                .expiresAt(now.minusSeconds(3600))
                .subject("1")
                .claim("email", "expired@example.com")
                .claim("role", "LISTENER")
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        return jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();
    }
}
