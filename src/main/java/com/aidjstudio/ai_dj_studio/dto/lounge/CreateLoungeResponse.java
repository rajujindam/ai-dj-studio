package com.aidjstudio.ai_dj_studio.dto.lounge;

import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateLoungeResponse {

    private Long loungeId;
    private String inviteCode;
    private LoungeStatus status;
    private Long hostUserId;
    private LocalDateTime createdAt;
}
