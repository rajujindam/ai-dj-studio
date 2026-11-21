package com.aidjstudio.ai_dj_studio.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("soundclash_battles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SoundclashBattle {

    @Id
    private Long id;

    @Column("lounge_id")
    private Long loungeId;

    @Column("opened_at")
    private LocalDateTime openedAt;

    @Column("closes_at")
    private LocalDateTime closesAt;

    private String status; // "OPEN", "RESOLVED", "CANCELLED"

    @Column("winning_track_id")
    private Long winningTrackId;
}
