
package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.auth.AuthResponse;
import com.aidjstudio.ai_dj_studio.dto.auth.LoginRequest;
import com.aidjstudio.ai_dj_studio.dto.auth.RegisterRequest;
import com.aidjstudio.ai_dj_studio.entity.Role;
import com.aidjstudio.ai_dj_studio.entity.User;
import com.aidjstudio.ai_dj_studio.exception.EmailAlreadyExistsException;
import com.aidjstudio.ai_dj_studio.exception.InvalidCredentialsException;
import com.aidjstudio.ai_dj_studio.repository.UserRepository;
import com.aidjstudio.ai_dj_studio.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public Mono<AuthResponse> register(RegisterRequest request) {
        String normalizedEmail = request.getEmail()
                .trim()
                .toLowerCase();

        return userRepository.existsByEmail(normalizedEmail)
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(
                                new EmailAlreadyExistsException(
                                        "Email is already registered"
                                )
                        );
                    }

                    User user = new User();
                    user.setEmail(normalizedEmail);
                    user.setPasswordHash(
                            passwordEncoder.encode(request.getPassword())
                    );
                    user.setDisplayName(request.getDisplayName().trim());
                    user.setRole(Role.LISTENER.name());

                    return userRepository.save(user)
                            .map(savedUser -> new AuthResponse(
                                    savedUser.getId(),
                                    savedUser.getEmail(),
                                    savedUser.getDisplayName(),
                                    savedUser.getRole(),
                                    null
                            ));
                });
    }

    public Mono<AuthResponse> login(LoginRequest request) {
        String normalizedEmail = request.getEmail()
                .trim()
                .toLowerCase();

        return userRepository.findByEmail(normalizedEmail)
                .filter(user -> passwordEncoder.matches(
                        request.getPassword(),
                        user.getPasswordHash()
                ))
                .switchIfEmpty(Mono.error(
                        new InvalidCredentialsException(
                                "Invalid email or password"
                        )
                ))
                .map(user -> {
                    String token = jwtService.generateToken(user);

                    AuthResponse response = new AuthResponse(
                            user.getId(),
                            user.getEmail(),
                            user.getDisplayName(),
                            user.getRole(),
                            token
                    );


                    return response;
                });
    }
}
