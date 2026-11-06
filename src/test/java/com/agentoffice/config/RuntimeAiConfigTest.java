package com.agentoffice.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeAiConfigTest {
    private static RuntimeAiConfig cfg(String provider, String planner) {
        var local = new LocalLlmProperties("http://192.168.1.184:11434/v1", "env-key", "ollama", "qwen-old",
                Map.of("local.small", "qwen-old", "planner", "qwen-old", "local.strong", "qwen-big"), Duration.ofSeconds(300), "none");
        var hermes = new HermesProperties("http://100.1.1.1:8642", "k", "", Duration.ofSeconds(300));
        return RuntimeAiConfig.of(local, hermes, new HermesAdminProperties("http://100.1.1.1:8650", "admin-env", ""), provider, planner);
    }

    @Test void defaultsComeFromEnv() {
        var c = cfg("local", "hermes").get();
        assertThat(c.local()).isTrue();
        assertThat(c.chatBaseUrl()).isEqualTo("http://192.168.1.184:11434/v1");
        assertThat(c.chatApiKey()).isEqualTo("env-key");
        assertThat(c.plannerMode()).isEqualTo("hermes");
        assertThat(c.hermesAdminKey()).isEqualTo("admin-env");
        assertThat(c.maxOutputTokens()).isEqualTo(2000);
    }

    @Test void overridesWinAndSecretsReplaceEnvKeys() {
        var cfg = cfg("local", "hermes");
        cfg.apply(Map.of(RuntimeAiConfig.TIMEOUT, "45", RuntimeAiConfig.OLLAMA_URL, "http://10.0.0.5:11434/v1"),
                Map.of(RuntimeAiConfig.SECRET_LLM, "db-key", RuntimeAiConfig.SECRET_ADMIN, "db-admin"));
        var c = cfg.get();
        assertThat(c.timeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(c.chatBaseUrl()).isEqualTo("http://10.0.0.5:11434/v1");
        assertThat(c.chatApiKey()).isEqualTo("db-key");
        assertThat(c.hermesAdminKey()).isEqualTo("db-admin");
        assertThat(cfg.secretSet(RuntimeAiConfig.SECRET_LLM)).isTrue();
        assertThat(cfg.secretSet(RuntimeAiConfig.SECRET_OMNI)).isFalse();
    }

    @Test void omnirouteTakesOverTheChatPathButNotTheOllamaAddress() {
        var cfg = cfg("local", "llm");
        cfg.apply(Map.of(RuntimeAiConfig.VIA_OMNI, "true", RuntimeAiConfig.OMNI_URL, "http://localhost:20128/v1"), Map.of(RuntimeAiConfig.SECRET_OMNI, "omni-key"));
        var c = cfg.get();
        assertThat(c.chatBaseUrl()).isEqualTo("http://localhost:20128/v1");
        assertThat(c.chatApiKey()).isEqualTo("omni-key");
        assertThat(c.ollamaBaseUrl()).isEqualTo("http://192.168.1.184:11434/v1");   // liệt kê/kiểm tra model vẫn hỏi thẳng Ollama
    }

    @Test void envAliasesPointingAtTheOldDefaultFollowTheNewDefault() {
        var cfg = cfg("local", "llm");
        cfg.apply(Map.of(RuntimeAiConfig.MODEL, "qwen-new"), Map.of());
        assertThat(cfg.get().aliases()).containsEntry("local.small", "qwen-new").containsEntry("planner", "qwen-new").containsEntry("local.strong", "qwen-big");
        // bảng định tuyến do người dùng đặt thì giữ nguyên, không bị đổi theo
        cfg.apply(Map.of(RuntimeAiConfig.MODEL, "qwen-new", RuntimeAiConfig.ALIASES, "{\"planner\":\"qwen-old\"}"), Map.of());
        assertThat(cfg.get().aliases()).containsOnly(Map.entry("planner", "qwen-old"));
    }

    @Test void plannerModeFollowsEnvUntilTheUserTouchesIt() {
        assertThat(cfg("local", "fake").get().plannerMode()).isEqualTo("fake");
        var cfg = cfg("local", "fake");
        cfg.apply(Map.of(RuntimeAiConfig.HERMES_ENABLED, "true", RuntimeAiConfig.HERMES_PLANNER, "hermes"), Map.of());
        assertThat(cfg.get().plannerMode()).isEqualTo("hermes");
        cfg.apply(Map.of(RuntimeAiConfig.HERMES_ENABLED, "false"), Map.of());
        assertThat(cfg.get().plannerMode()).isEqualTo("llm");          // tắt Hermes + AI thật -> LLM lập kế hoạch
        var fake = cfg("fake", "hermes");
        fake.apply(Map.of(RuntimeAiConfig.HERMES_ENABLED, "false"), Map.of());
        assertThat(fake.get().plannerMode()).isEqualTo("fake");        // AI giả thì không có LLM thật để lập kế hoạch
    }
}
