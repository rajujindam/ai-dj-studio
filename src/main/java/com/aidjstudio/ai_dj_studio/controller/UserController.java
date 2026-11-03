package com.aidjstudio.ai_dj_studio.controller;

import com.aidjstudio.ai_dj_studio.dto.user.UserProfileResponse;
import com.aidjstudio.ai_dj_studio.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public Mono<UserProfileResponse> getMyProfile(
            @RequestParam Long userId
    ) {
        return userService.getProfile(userId);
    }
}