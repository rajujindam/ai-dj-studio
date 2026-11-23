package com.aidjstudio.ai_dj_studio.dto.track;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RetrievedMetadataEntry {
    private Long id;
    private Long trackId;
    private String sourceId;
    private Integer tsSeconds;
    private String eventType;
    private String text;
    private Double score;
}
