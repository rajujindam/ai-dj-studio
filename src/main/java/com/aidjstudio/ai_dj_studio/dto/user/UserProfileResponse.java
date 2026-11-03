
package com.aidjstudio.ai_dj_studio.dto.user;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserProfileResponse {
    private Long id;
    private String email;
    private String displayName;
    private String role;
}
