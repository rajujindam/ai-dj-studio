package com.aidjstudio.ai_dj_studio.dto.moderation;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferHostRequest {

    @NotNull(message = "New host user ID is required")
    private Long newHostUserId;
}
