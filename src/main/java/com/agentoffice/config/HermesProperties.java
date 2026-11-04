package com.agentoffice.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** agentoffice.hermes.* (modelCheckUrl trống = bỏ qua bước ping model). */
@Validated
@ConfigurationProperties("agentoffice.hermes")
public record HermesProperties(
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("") String modelCheckUrl,
        @DefaultValue("300s") @NotNull Duration stallTimeout) {}
