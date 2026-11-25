package com.aidjstudio.ai_dj_studio.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("interactions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Interaction {

    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    @Column("track_id")
    private Long trackId;

    @Column("lounge_id")
    private Long loungeId;

    @Column("played_seconds")
    @Builder.Default
    private Integer playedSeconds = 0;

    @Builder.Default
    private Boolean completed = false;

    @Builder.Default
    private Boolean liked = false;

    @Column("created_at")
    private LocalDateTime createdAt;
}
