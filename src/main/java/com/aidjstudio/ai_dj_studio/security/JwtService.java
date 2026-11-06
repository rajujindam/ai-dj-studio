package com.aidjstudio.ai_dj_studio.security;

import com.aidjstudio.ai_dj_studio.config.JwtProperties;
import com.aidjstudio.ai_dj_studio.entity.Role;
import com.aidjstudio.ai_dj_studio.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    public String generateToken(User user) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusMillis(jwtProperties.getExpiration());

        String role = user.getRole();

        if ("USER".equals(role)) {
            role = Role.LISTENER.name();
        }

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ai-dj-studio")
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", role)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        JwtEncoderParameters parameters =
                JwtEncoderParameters.from(header, claims);

        Jwt generatedJwt = jwtEncoder.encode(parameters);
        String token = generatedJwt.getTokenValue();

        return token;
    }
}