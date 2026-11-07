package com.aidjstudio.ai_dj_studio.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("lounge_participants")
@Data
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

    @Column("joined_at")
    private LocalDateTime joinedAt;

    @Column("left_at")
    private LocalDateTime leftAt;
}