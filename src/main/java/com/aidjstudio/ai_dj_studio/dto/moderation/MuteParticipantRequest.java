package com.aidjstudio.ai_dj_studio.dto.moderation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MuteParticipantRequest {

    @Builder.Default
    private Boolean muted = true;
}
