package com.aidjstudio.ai_dj_studio.dto.soundclash;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenSoundclashBattleRequest {

    /**
     * Duration of the battle in seconds (default 60s, min 15s, max 300s).
     */
    private Integer durationSeconds;

    /**
     * 2 to 4 candidates to compete in the battle.
     */
    @NotNull(message = "Candidate track IDs cannot be null")
    @Size(min = 2, max = 4, message = "Soundclash battle must have between 2 and 4 candidate tracks")
    private List<Long> trackIds;
}
