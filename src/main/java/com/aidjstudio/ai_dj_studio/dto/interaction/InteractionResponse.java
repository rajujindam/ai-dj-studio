package com.aidjstudio.ai_dj_studio.dto.interaction;

import com.aidjstudio.ai_dj_studio.entity.Interaction;
import com.aidjstudio.ai_dj_studio.entity.Track;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InteractionResponse {

    private Long id;
    private Long userId;
    private Long trackId;
    private String trackTitle;
    private String trackArtist;
    private Long loungeId;
    private Integer playedSeconds;
    private Boolean completed;
    private Boolean liked;
    private LocalDateTime createdAt;

    public static InteractionResponse fromEntity(Interaction interaction, Track track) {
        return InteractionResponse.builder()
                .id(interaction.getId())
                .userId(interaction.getUserId())
                .trackId(interaction.getTrackId())
                .trackTitle(track != null ? track.getTitle() : null)
                .trackArtist(track != null ? track.getArtist() : null)
                .loungeId(interaction.getLoungeId())
                .playedSeconds(interaction.getPlayedSeconds())
                .completed(interaction.getCompleted())
                .liked(interaction.getLiked())
                .createdAt(interaction.getCreatedAt())
                .build();
    }
}
