package com.aidjstudio.ai_dj_studio.dto.queue;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddQueueEntryRequest {

    @NotNull(message = "Track ID is required")
    private Long trackId;
}
