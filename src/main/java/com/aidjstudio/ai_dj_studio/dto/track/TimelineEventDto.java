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
public class TimelineEventDto {
    private Long id;
    private Integer ts;
    private String type;
    private String text;
}
