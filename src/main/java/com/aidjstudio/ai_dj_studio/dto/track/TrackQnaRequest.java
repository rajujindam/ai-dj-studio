package com.aidjstudio.ai_dj_studio.dto.track;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrackQnaRequest {

    @NotBlank(message = "Question must not be blank")
    @Size(max = 250, message = "Question must not exceed 250 characters")
    private String question;
}
