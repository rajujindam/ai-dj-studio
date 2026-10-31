package com.aidjstudio.ai_dj_studio.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;

@Table("tracks")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Track {

    @Id
    private Long id;

    private String title;

    private String artist;

    private Integer durationSeconds;

    private String audioAssetUrl;

    private BigDecimal bpm;

    private String camelotKey;

    private BigDecimal energy;

    private BigDecimal valence;
}