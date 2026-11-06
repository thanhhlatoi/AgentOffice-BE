package com.agentoffice.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * agentoffice.llm.local.* : máy AI local theo chuẩn OpenAI (Ollama, LM Studio, vLLM...). Chỉ dùng khi agentoffice.llm.provider=local.
 * baseUrl gồm cả /v1 (Ollama: http://localhost:11434/v1). providerName phải khớp model_price.provider (seed: "ollama").
 * reasoningEffort: gửi "reasoning_effort" cho model suy luận (qwen3.x...); "none" = tắt phần nghĩ để không ngốn hết maxOutputTokens
 * rồi trả content rỗng. Trống = không gửi (server không hỗ trợ trường này thì để trống).
 * defaultModel: dùng khi tên model của request không phải model thật (vd "fake", "planner"); modelAliases: tên profile -> model thật.
 */
@Validated
@ConfigurationProperties("agentoffice.llm.local")
public record LocalLlmProperties(
        @DefaultValue("http://localhost:11434/v1") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("ollama") String providerName,
        @DefaultValue("qwen3:4b") String defaultModel,
        @DefaultValue Map<String, String> modelAliases,
        @DefaultValue("300s") @NotNull Duration timeout,
        @DefaultValue("") String reasoningEffort) {}
