package com.agentoffice.client.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.config.LlmProperties;
import com.agentoffice.domain.usage.CallContext;
import com.agentoffice.domain.usage.LlmCallException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Chạy client với HttpServer của JDK, đi đúng đường HTTP thật; không cần Docker. */
class OpenAiCompatibleLlmClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<String> path = new AtomicReference<>(), auth = new AtomicReference<>(), body = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply;
    private HttpServer server;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            path.set(ex.getRequestURI().getPath());
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private OpenAiCompatibleLlmClient client(String baseUrl, String key) {
        return new OpenAiCompatibleLlmClient(
                new LlmProperties("openai-compatible", new LlmProperties.Gateway(baseUrl, key, Duration.ofSeconds(2), Duration.ofSeconds(5))), mapper);
    }

    private OpenAiCompatibleLlmClient client() { return client("http://127.0.0.1:" + server.getAddress().getPort() + "/", "sk-test"); }

    private static LlmRequest request() {
        return new LlmRequest("writer", List.of(new LlmRequest.Msg("system", "s"), new LlmRequest.Msg("user", "xin chào")), 256,
                new CallContext(null, null, null, null, null, CallContext.Purpose.AGENT_RUN, null));
    }

    @Test void successParsesTextAndUsage() throws IOException {
        reply = "{\"model\":\"writer\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"chào bạn\"}}],"
                + "\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5,\"prompt_tokens_details\":{\"cached_tokens\":4}}}";

        LlmResponse r = client().complete(request());

        assertThat(r.text()).isEqualTo("chào bạn");
        assertThat(r.inputTokens()).isEqualTo(12);
        assertThat(r.outputTokens()).isEqualTo(5);
        assertThat(r.cachedTokens()).isEqualTo(4);
        assertThat(path.get()).isEqualTo("/v1/chat/completions");   // dấu / cuối base-url không sinh //
        JsonNode sent = mapper.readTree(body.get());
        assertThat(sent.path("model").asText()).isEqualTo("writer");
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(256);
        assertThat(sent.path("stream").asBoolean(true)).isFalse();
        assertThat(sent.path("messages")).hasSize(2);
        assertThat(sent.path("messages").path(1).path("content").asText()).isEqualTo("xin chào");
    }

    @Test void missingUsageGivesZeroSoMeteringEstimates() {
        reply = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";

        LlmResponse r = client().complete(request());

        assertThat(r.text()).isEqualTo("ok");
        assertThat(r.inputTokens()).isZero();
        assertThat(r.outputTokens()).isZero();
        assertThat(r.model()).isEqualTo("writer");
    }

    @Test void sendsBearerKeyAndOmitsHeaderWhenKeyBlank() {
        reply = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}";
        client().complete(request());
        assertThat(auth.get()).isEqualTo("Bearer sk-test");

        client("http://127.0.0.1:" + server.getAddress().getPort(), "").complete(request());
        assertThat(auth.get()).isNull();
    }

    @Test void malformedSuccessBodyIsPermanentError() {
        reply = "{\"choices\":[]}";
        assertThatThrownBy(() -> client().complete(request()))
                .isInstanceOfSatisfying(LlmCallException.class, e -> assertThat(e.retryable()).isFalse());
    }

    @Test void listsModelsWithBearerKey() {
        reply = "{\"object\":\"list\",\"data\":[{\"id\":\"cx/gpt-6-luna\",\"owned_by\":\"cx\",\"capabilities\":{\"vision\":true,\"tools\":false,\"reasoning\":true,\"contextWindow\":272000,\"maxOutput\":128000}},{\"id\":\"writer\"},{\"owned_by\":\"x\"}]}";

        List<LlmModel> models = client().models();

        assertThat(path.get()).isEqualTo("/v1/models");
        assertThat(auth.get()).isEqualTo("Bearer sk-test");
        assertThat(models).containsExactly(new LlmModel("cx/gpt-6-luna", "cx", 272000L, 128000L, true, false, true), new LlmModel("writer", null));   // bỏ mục thiếu id
    }

    @Test void providerNameMatchesModelPriceColumn() {
        assertThat(client().provider()).isEqualTo("9router");
    }

    @Test void blankBaseUrlFailsAtStartup() {
        assertThatThrownBy(() -> client("  ", "k")).isInstanceOf(IllegalStateException.class).hasMessageContaining("NINEROUTER_URL");
    }
}
