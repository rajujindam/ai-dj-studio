package com.aidjstudio.ai_dj_studio.controller;

import com.aidjstudio.ai_dj_studio.dto.auth.AuthResponse;
import com.aidjstudio.ai_dj_studio.dto.auth.LoginRequest;
import com.aidjstudio.ai_dj_studio.dto.auth.RegisterRequest;
import com.aidjstudio.ai_dj_studio.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public Mono<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public Mono<AuthResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {
        return authService.login(request);
    }
}