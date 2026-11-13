package com.aidjstudio.ai_dj_studio.dto.websocket;

import com.aidjstudio.ai_dj_studio.dto.queue.QueueEntryResponse;
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
public class LoungeSnapshotPayload {
    private Long loungeId;
    private LoungeStatus status;
    private Long hostUserId;
    private boolean hostConnected;
    private boolean queueFrozen;
    private Long currentTrackId;
    private List<QueueEntryResponse> nextUp;
    private Object activeSoundclash;
    private int participantCount;
}
