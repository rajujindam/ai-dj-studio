package com.aidjstudio.ai_dj_studio.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("soundclash_votes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SoundclashVote {

    @Id
    private Long id;

    @Column("battle_id")
    private Long battleId;

    @Column("candidate_id")
    private Long candidateId;

    @Column("user_id")
    private Long userId;

    @Column("created_at")
    private LocalDateTime createdAt;
}
