package com.aidjstudio.ai_dj_studio.dto.soundclash;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SoundclashBattleResponse {

    private Long id;
    private Long loungeId;
    private LocalDateTime openedAt;
    private LocalDateTime closesAt;
    private String status;
    private Long winningTrackId;
    private List<SoundclashCandidateResponse> candidates;
}
