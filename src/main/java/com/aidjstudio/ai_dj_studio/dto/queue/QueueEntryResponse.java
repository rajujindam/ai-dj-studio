package com.aidjstudio.ai_dj_studio.dto.queue;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueEntryResponse {

    private Long id;
    private Long loungeId;
    private Long trackId;
    private Integer position;
    private Integer voteCount;
    private Long addedByUserId;
}
