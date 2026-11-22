package com.aidjstudio.ai_dj_studio.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("lounge_participants")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoungeParticipant {

    @Id
    private Long id;

    @Column("lounge_id")
    private Long loungeId;

    @Column("user_id")
    private Long userId;

    private LoungeParticipantRole role;

    @Column("is_muted")
    @Builder.Default
    private Boolean isMuted = false;

    @Column("joined_at")
    private LocalDateTime joinedAt;

    @Column("left_at")
    private LocalDateTime leftAt;

    public LoungeParticipant(Long id, Long loungeId, Long userId, LoungeParticipantRole role, LocalDateTime joinedAt, LocalDateTime leftAt) {
        this(id, loungeId, userId, role, false, joinedAt, leftAt);
    }
}
