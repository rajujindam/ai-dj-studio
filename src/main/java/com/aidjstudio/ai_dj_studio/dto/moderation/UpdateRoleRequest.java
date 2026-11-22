package com.aidjstudio.ai_dj_studio.dto.moderation;

import com.aidjstudio.ai_dj_studio.entity.LoungeParticipantRole;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateRoleRequest {

    @NotNull(message = "Role is required")
    private LoungeParticipantRole role;
}
