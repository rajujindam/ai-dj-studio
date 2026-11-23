package com.aidjstudio.ai_dj_studio.config;

import com.aidjstudio.ai_dj_studio.service.llm.FakeLlmProvider;
import com.aidjstudio.ai_dj_studio.service.llm.LlmProvider;
import com.aidjstudio.ai_dj_studio.service.llm.OpenAiCompatibleLlmProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class LlmConfig {

    @Bean
    @ConditionalOnMissingBean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    @Primary
    public LlmProvider llmProvider(
            @Value("${app.llm.provider:fake}") String providerType,
            FakeLlmProvider fakeLlmProvider,
            OpenAiCompatibleLlmProvider openAiCompatibleLlmProvider
    ) {
        if ("openai".equalsIgnoreCase(providerType) || "remote".equalsIgnoreCase(providerType)) {
            return openAiCompatibleLlmProvider;
        }
        return fakeLlmProvider;
    }
}
