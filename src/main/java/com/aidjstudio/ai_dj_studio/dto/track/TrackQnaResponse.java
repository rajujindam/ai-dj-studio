package com.aidjstudio.ai_dj_studio.dto.track;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TrackQnaResponse {

    private String answer;
    private List<String> sourceIds;
    private List<RetrievedMetadataEntry> sources;
    private boolean grounded;
}
