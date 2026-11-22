package com.aidjstudio.ai_dj_studio.dto.soundclash;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoteSoundclashRequest {

    @NotNull(message = "Candidate ID is required")
    private Long candidateId;
}
