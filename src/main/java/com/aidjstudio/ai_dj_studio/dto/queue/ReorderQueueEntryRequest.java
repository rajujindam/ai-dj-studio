package com.aidjstudio.ai_dj_studio.dto.queue;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReorderQueueEntryRequest {

    @NotNull(message = "Target position is required")
    @Min(value = 1, message = "Target position must be at least 1")
    private Integer targetPosition;
}
