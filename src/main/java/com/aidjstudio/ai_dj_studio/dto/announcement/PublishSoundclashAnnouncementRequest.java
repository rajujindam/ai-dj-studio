package com.aidjstudio.ai_dj_studio.dto.announcement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishSoundclashAnnouncementRequest {
    private Long battleId;
}
