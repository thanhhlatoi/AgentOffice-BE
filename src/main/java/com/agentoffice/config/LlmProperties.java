package com.agentoffice.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** agentoffice.llm.*: provider = fake | openai-compatible. gateway.* chỉ dùng khi openai-compatible (9Router). */
@Validated
@ConfigurationProperties("agentoffice.llm")
public record LlmProperties(
        @DefaultValue("fake") @NotNull String provider,
        @DefaultValue @Valid @NotNull Gateway gateway) {

    public record Gateway(
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("5s") @NotNull Duration connectTimeout,
            @DefaultValue("120s") @NotNull Duration readTimeout) {}
}
