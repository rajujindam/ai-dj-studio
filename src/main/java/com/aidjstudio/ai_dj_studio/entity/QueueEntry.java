package com.aidjstudio.ai_dj_studio.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("queue_entries")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueueEntry {

    @Id
    private Long id;

    @Column("lounge_id")
    private Long loungeId;

    @Column("track_id")
    private Long trackId;

    private Integer position;

    @Column("vote_count")
    private Integer voteCount;

    @Column("added_by_user_id")
    private Long addedByUserId;
}