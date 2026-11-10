package com.aidjstudio.ai_dj_studio.dto.track;

import com.aidjstudio.ai_dj_studio.entity.Track;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrackResponse {

    private Long id;
    private String title;
    private String artist;
    private Integer durationSeconds;
    private String audioAssetUrl;
    private BigDecimal bpm;
    private String camelotKey;
    private BigDecimal energy;
    private BigDecimal valence;

    public static TrackResponse fromEntity(Track track) {
        if (track == null) {
            return null;
        }
        return new TrackResponse(
                track.getId(),
                track.getTitle(),
                track.getArtist(),
                track.getDurationSeconds(),
                track.getAudioAssetUrl(),
                track.getBpm(),
                track.getCamelotKey(),
                track.getEnergy(),
                track.getValence()
        );
    }
}
