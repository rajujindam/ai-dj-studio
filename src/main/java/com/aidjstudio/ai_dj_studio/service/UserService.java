
package com.aidjstudio.ai_dj_studio.service;

import com.aidjstudio.ai_dj_studio.dto.user.UserProfileResponse;
import com.aidjstudio.ai_dj_studio.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public Mono<UserProfileResponse> getProfile(Long userId) {
        return userRepository.findById(userId)
                .map(user -> new UserProfileResponse(
                        user.getId(),
                        user.getEmail(),
                        user.getDisplayName(),
                        user.getRole()
                ));
    }
}
