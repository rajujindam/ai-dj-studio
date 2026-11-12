package com.aidjstudio.ai_dj_studio.dto.queue;

import com.aidjstudio.ai_dj_studio.entity.LoungeStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoungePlaybackStateResponse {

    private Long loungeId;
    private LoungeStatus status;
    private Long currentTrackId;
    private List<QueueEntryResponse> nextUp;
}
