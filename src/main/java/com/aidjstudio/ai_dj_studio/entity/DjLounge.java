package com.aidjstudio.ai_dj_studio.entity;

import io.r2dbc.postgresql.codec.Json;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Table("dj_lounges")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DjLounge {

    @Id
    private Long id;

    @Column("host_user_id")
    private Long hostUserId;

    @Column("vibe_profile")
    private Json vibeProfile;

    @Column("current_track_id")
    private Long currentTrackId;

    private LoungeStatus status;

    @Column("bpm_tolerance_pct")
    private BigDecimal bpmTolerancePct;

    @Column("soundclash_enabled")
    private Boolean soundclashEnabled;

    @Column("ai_verbosity")
    private String aiVerbosity;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("ended_at")
    private LocalDateTime endedAt;
}