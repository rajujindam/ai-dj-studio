package com.aidjstudio.ai_dj_studio.dto.websocket;

import com.aidjstudio.ai_dj_studio.dto.queue.QueueEntryResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WsQueueUpdatePayload {
    private Long loungeId;
    private String reason;
    private List<QueueEntryResponse> queue;
    private List<QueueEntryResponse> nextUp;
}
