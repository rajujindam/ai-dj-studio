package com.aidjstudio.ai_dj_studio.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("soundclash_candidates")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SoundclashCandidate {

    @Id
    private Long id;

    @Column("battle_id")
    private Long battleId;

    @Column("track_id")
    private Long trackId;

    @Column("submitted_by_user_id")
    private Long submittedByUserId;

    @Column("vote_count")
    private Integer voteCount;
}
