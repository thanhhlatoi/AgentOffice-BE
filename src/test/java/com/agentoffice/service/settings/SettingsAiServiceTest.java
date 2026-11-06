package com.agentoffice.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agentoffice.client.hermes.HermesBotClient;
import com.agentoffice.client.llm.OllamaClient;
import com.agentoffice.config.HermesAdminProperties;
import com.agentoffice.config.HermesProperties;
import com.agentoffice.config.LocalLlmProperties;
import com.agentoffice.config.RuntimeAiConfig;
import com.agentoffice.dto.fe.SettingsAiDto;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.security.OwnerAuth;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SettingsAiServiceTest {
    private static final String SECRET_LLM = "sk-local-SECRET-123";
    private static final String SECRET_HERMES = "hermes-SECRET-456";
    private static final String SECRET_ADMIN = "admin-SECRET-789-padding-padding";
    private static final String OWNER = "0123456789abcdef0123456789abcdef";
    private final ObjectMapper json = new ObjectMapper();

    private RuntimeAiConfig cfg;
    private AiSettingsStore store;

    private SettingsAiService service(String provider, String planner, String ownerToken) {
        var local = new LocalLlmProperties("http://192.168.1.184:11434/v1", SECRET_LLM, "ollama", "qwen3.6-64k:latest",
                Map.of("planner", "qwen3.6-64k:latest"), Duration.ofSeconds(300), "none");
        var hermes = new HermesProperties("http://100.1.1.1:8642", SECRET_HERMES, "http://192.168.1.184:11434/api/tags", Duration.ofSeconds(300));
        var admin = new HermesAdminProperties("http://100.1.1.1:8650", SECRET_ADMIN, "");
        cfg = RuntimeAiConfig.of(local, hermes, admin, provider, planner);
        store = mock(AiSettingsStore.class);
        var crypto = new SettingsCrypto(Base64.getEncoder().encodeToString(new byte[32]));
        return new SettingsAiService(cfg, hermes, new HermesBotClient(admin, json, cfg), new OllamaClient(cfg, json), store,
                new UrlGuard(""), new OwnerAuth(ownerToken), json);
    }

    private SettingsAiService service() { return service("local", "hermes", OWNER); }

    // ------------------------------------------------------------------ đọc
    @Test void settingsNeverContainAnyKeyValue() throws Exception {
        String out = json.writeValueAsString(service().settings());
        assertThat(out).doesNotContain(SECRET_LLM, SECRET_HERMES, SECRET_ADMIN);
        SettingsAiDto.Settings s = service().settings();
        assertThat(s.llm().apiKeySet()).isTrue();          // chỉ báo đã đặt khoá
        assertThat(s.hermes().adminConfigured()).isTrue();
    }

    @Test void sourceIsDatabaseOnlyWhenTheOwnerTokenIsSet() {
        assertThat(service("local", "hermes", OWNER).settings().source()).isEqualTo("database");
        assertThat(service("local", "hermes", OWNER).settings().authEnabled()).isTrue();
        assertThat(service("local", "hermes", "").settings().source()).isEqualTo("env");
        assertThat(service().settings().applyMode()).isEqualTo("live");
    }

    @Test void providerAndPlannerMapToFrontendEnums() {
        assertThat(service("fake", "fake", OWNER).settings().llm().provider()).isEqualTo("fake");
        assertThat(service("fake", "fake", OWNER).settings().hermes().planner()).isEqualTo("llm");   // FE chỉ có hermes | llm
        assertThat(service().settings().hermes().enabled()).isTrue();
    }

    @Test void testRejectsUnknownTargetAndNeverCallsAnAddressTheClientSupplied() {
        assertThatThrownBy(() -> service().test(new SettingsAiDto.TestBody("x", null)))
                .isInstanceOf(AppException.class).extracting(e -> ((AppException) e).code()).isEqualTo(ErrorCode.VALIDATION);
        SettingsAiDto.TestResult r = service().test(new SettingsAiDto.TestBody("omniroute", Map.of("baseUrl", "http://169.254.169.254/")));
        assertThat(r.ok()).isFalse();                       // OmniRoute chưa bật: thất bại có mã lỗi, không gọi ra ngoài
        assertThat(r.errorCode()).isEqualTo("LLM_UNAVAILABLE");
    }

    // ------------------------------------------------------------------ ghi
    private void put(SettingsAiService s, String body) throws Exception { s.update(json.readTree(body)); }

    @Test void validPatchIsWrittenAsPlainValuesAndEncryptedSecretsAreSeparate() throws Exception {
        SettingsAiService s = service();
        put(s, "{\"llm\":{\"timeoutSec\":60,\"reasoningEffort\":\"low\",\"modelAliases\":{\"planner\":\"qwen3.6:35b\"}},"
                + "\"ollama\":{\"baseUrl\":\"http://10.0.0.5:11434/v1/\",\"defaultModel\":\"qwen3.6:35b\"},\"hermes\":{\"planner\":\"llm\"}}");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> plain = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> secrets = ArgumentCaptor.forClass(Map.class);
        verify(store).save(plain.capture(), secrets.capture());
        assertThat(plain.getValue()).containsEntry(RuntimeAiConfig.TIMEOUT, "60").containsEntry(RuntimeAiConfig.REASONING, "low")
                .containsEntry(RuntimeAiConfig.OLLAMA_URL, "http://10.0.0.5:11434/v1").containsEntry(RuntimeAiConfig.MODEL, "qwen3.6:35b")
                .containsEntry(RuntimeAiConfig.HERMES_PLANNER, "llm").containsEntry(RuntimeAiConfig.ALIASES, "{\"planner\":\"qwen3.6:35b\"}");
        assertThat(secrets.getValue()).isEmpty();
        verify(store).reload();
    }

    @Test void keysAreForwardedToTheStoreForEncryptionAndNullDeletes() throws Exception {
        SettingsAiService s = service();
        put(s, "{\"llm\":{\"apiKey\":\"  sk-new  \"},\"omniroute\":{\"apiKey\":null},\"hermes\":{\"adminToken\":\"tok-123\"}}");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> secrets = ArgumentCaptor.forClass(Map.class);
        verify(store).save(anyMap(), secrets.capture());
        assertThat(secrets.getValue()).containsEntry(RuntimeAiConfig.SECRET_LLM, "sk-new").containsEntry(RuntimeAiConfig.SECRET_ADMIN, "tok-123")
                .containsKey(RuntimeAiConfig.SECRET_OMNI);
        assertThat(secrets.getValue().get(RuntimeAiConfig.SECRET_OMNI)).isNull();
    }

    @Test void anyInvalidFieldRejectsTheWholePatchBeforeWritingAnything() {
        SettingsAiService s = service();
        String[] bad = {
                "{}", "[]", "{\"unknown\":{}}", "{\"llm\":{\"nope\":1}}", "{\"llm\":{\"provider\":\"cloud\"}}", "{\"llm\":{\"timeoutSec\":0}}",
                "{\"llm\":{\"timeoutSec\":\"60\"}}", "{\"llm\":{\"maxOutputTokens\":999999}}", "{\"llm\":{\"reasoningEffort\":\"max\"}}",
                "{\"llm\":{\"modelAliases\":{\"bad key!\":\"x\"}}}", "{\"llm\":{\"modelAliases\":{\"a\":\"\"}}}",
                "{\"ollama\":{\"baseUrl\":\"http://169.254.169.254/\"}}", "{\"ollama\":{\"baseUrl\":\"http://8.8.8.8/v1\"}}",
                "{\"ollama\":{\"baseUrl\":\"ftp://192.168.1.1\"}}", "{\"ollama\":{\"defaultModel\":\"\"}}", "{\"ollama\":{\"defaultModel\":\"a\\nb\"}}",
                "{\"hermes\":{\"planner\":\"openai\"}}", "{\"hermes\":{\"baseUrl\":\"http://user:p@192.168.1.5\"}}",
                "{\"llm\":{\"viaOmniroute\":true},\"omniroute\":{\"enabled\":false}}", "{\"llm\":{\"apiKey\":\"\"}}", "{\"llm\":{\"apiKey\":5}}",
                "{\"llm\":{\"apiKey\":\"a\\nb\"}}", "{\"hermes\":{\"enabled\":true,\"baseUrl\":\"\"}}",
                "{\"llm\":{\"timeoutSec\":30},\"ollama\":{\"baseUrl\":\"http://8.8.8.8/\"}}"};   // một trường sai kéo cả patch bị từ chối
        for (String b : bad)
            assertThatThrownBy(() -> put(s, b)).as(b).satisfies(e -> assertThat(e).isInstanceOf(AppException.class));
        verify(store, never()).save(anyMap(), anyMap());
        verify(store, never()).reload();
    }

    @Test void bothOmnirouteFlagsMapToOneSetting() throws Exception {
        SettingsAiService s = service();
        put(s, "{\"llm\":{\"viaOmniroute\":true},\"omniroute\":{\"enabled\":true,\"baseUrl\":\"http://localhost:20128/v1\"}}");
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, String>> plain = ArgumentCaptor.forClass(Map.class);
        verify(store).save(plain.capture(), anyMap());
        assertThat(plain.getValue()).containsEntry(RuntimeAiConfig.VIA_OMNI, "true").containsEntry(RuntimeAiConfig.OMNI_URL, "http://localhost:20128/v1");
    }
}
