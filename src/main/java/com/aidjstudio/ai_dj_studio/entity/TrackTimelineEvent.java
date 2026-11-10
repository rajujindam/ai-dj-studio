package com.aidjstudio.ai_dj_studio.entity;

import io.r2dbc.postgresql.codec.Json;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("track_timeline_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrackTimelineEvent {

    @Id
    private Long id;

    @Column("track_id")
    private Long trackId;

    @Column("ts_seconds")
    private Integer tsSeconds;

    @Column("event_type")
    private String eventType;

    private Json payload;
}
