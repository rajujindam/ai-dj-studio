package com.aidjstudio.ai_dj_studio.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("queue_votes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueueVote {

    @Id
    private Long id;

    @Column("lounge_id")
    private Long loungeId;

    @Column("queue_entry_id")
    private Long queueEntryId;

    @Column("user_id")
    private Long userId;

    @Column("created_at")
    private LocalDateTime createdAt;
}
