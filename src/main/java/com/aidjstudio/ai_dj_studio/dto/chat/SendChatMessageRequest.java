package com.aidjstudio.ai_dj_studio.dto.chat;

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
public class SendChatMessageRequest {

    @NotBlank(message = "Message body cannot be blank")
    @Size(max = 500, message = "Message body cannot exceed 500 characters")
    private String body;

    private String msgType; // Defaults to "TEXT" if null/blank
}
