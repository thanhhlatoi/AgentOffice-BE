package com.agentoffice.dto.fe;

import java.util.List;
import java.util.Map;

/** Hợp đồng /settings/ai của FE (D:\FE\AI-Company-Office\src\types\settingsAi.ts). TUYỆT ĐỐI không có trường chứa giá trị khoá. */
public final class SettingsAiDto {
    private SettingsAiDto() {}

    public record Llm(String provider, String baseUrl, String model, String reasoningEffort, int timeoutSec, int maxOutputTokens,
                      boolean apiKeySet, boolean viaOmniroute, Map<String, String> modelAliases) {}

    public record Ollama(String baseUrl, String defaultModel) {}

    public record Omniroute(boolean enabled, String baseUrl, String dashboardUrl, boolean apiKeySet) {}

    /** healthy luôn có mặt trong JSON (null = chưa kiểm; FE lấy tình trạng thật ở /status). */
    public record Hermes(boolean enabled, String planner, String baseUrl, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS) Boolean healthy, boolean adminConfigured) {}

    public record Settings(String applyMode, String source, boolean authEnabled, Llm llm, Ollama ollama, Omniroute omniroute, Hermes hermes) {}

    public record Health(String state, Long latencyMs, String checkedAt, String detail) {}

    public record Status(Health ollama, Health omniroute, Health llm, Health hermes) {}

    public record ModelItem(String id, String label, Boolean supportsTools, Boolean thinking) {}

    public record Models(List<ModelItem> models) {}

    public record TestBody(String target, Map<String, String> overrides) {}

    public record TestResult(boolean ok, long latencyMs, String message, String errorCode, String requestId, List<String> models, String sample) {}
}
