package com.aidjstudio.ai_dj_studio.dto.interaction;

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
public class RecordInteractionRequest {

    @NotNull(message = "Track ID is required")
    private Long trackId;

    @Min(value = 0, message = "Played seconds must be non-negative")
    @Builder.Default
    private Integer playedSeconds = 0;

    @Builder.Default
    private Boolean completed = false;

    @Builder.Default
    private Boolean liked = false;

    private Long loungeId;
}
