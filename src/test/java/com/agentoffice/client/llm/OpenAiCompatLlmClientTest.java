package com.agentoffice.client.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.config.LocalLlmProperties;
import com.agentoffice.domain.usage.CallContext;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatLlmClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "{}";
    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            lastAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    private OpenAiCompatLlmClient client(String apiKey) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
        return new OpenAiCompatLlmClient(new LocalLlmProperties(url, apiKey, "ollama", "qwen3:4b",
                Map.of("local.strong", "qwen3:32b"), Duration.ofSeconds(5), "none"), mapper);
    }

    private static LlmRequest req(String model) {
        return new LlmRequest(model, List.of(new LlmRequest.Msg("user", "chào")), 128, CallContext.forPlanner(java.util.UUID.randomUUID()));
    }

    @Test
    void doc_cau_tra_loi_va_token_va_bo_phan_think() throws Exception {
        reply = "{\"model\":\"qwen3:4b\",\"choices\":[{\"message\":{\"content\":\"<think>nghĩ...</think>\\nXin chào\"}}],"
                + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}";
        LlmResponse r = client("").complete(req("fake"));
        assertThat(r.text()).isEqualTo("Xin chào");
        assertThat(r.inputTokens()).isEqualTo(7);
        assertThat(r.outputTokens()).isEqualTo(3);
        assertThat(mapper.readTree(lastBody.get()).path("model").asText()).isEqualTo("qwen3:4b"); // "fake" -> model mặc định
        assertThat(lastAuth.get()).isNull();
        assertThat(mapper.readTree(lastBody.get()).path("reasoning_effort").asText()).isEqualTo("none");
    }

    @Test
    void alias_profile_doi_thanh_model_that_va_gui_api_key() throws Exception {
        reply = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        client("k123").complete(req("local.strong"));
        assertThat(mapper.readTree(lastBody.get()).path("model").asText()).isEqualTo("qwen3:32b");
        assertThat(lastAuth.get()).isEqualTo("Bearer k123");
    }

    @Test
    void ten_model_that_giu_nguyen() {
        assertThat(client("").resolveModel("llama3.1:8b")).isEqualTo("llama3.1:8b");
    }

    @Test
    void http_loi_thanh_llm_unavailable() {
        status = 500;
        assertThatThrownBy(() -> client("").complete(req("fake")))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_UNAVAILABLE));
    }

    @Test
    void khong_noi_dung_la_loi() {
        reply = "{\"choices\":[{\"message\":{\"content\":\"\"}}]}";
        assertThatThrownBy(() -> client("").complete(req("fake"))).isInstanceOf(AppException.class);
    }
}
