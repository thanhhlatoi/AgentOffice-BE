package com.agentoffice.client.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.config.LlmProperties;
import com.agentoffice.domain.usage.CallContext;
import com.agentoffice.domain.usage.LlmCallException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Đường lỗi đầu-cuối của client: dựa vào LlmErrorMapper nên ĐỎ cho tới khi bạn viết xong lõi. */
@Tag("core")
class OpenAiCompatibleLlmClientErrorTest {
    private static final LlmRequest REQ = new LlmRequest("writer", List.of(new LlmRequest.Msg("user", "hi")), 16,
            new CallContext(null, null, null, null, null, CallContext.Purpose.AGENT_RUN, null));

    private static OpenAiCompatibleLlmClient clientFor(String url, Duration read) {
        return new OpenAiCompatibleLlmClient(new LlmProperties("openai-compatible",
                new LlmProperties.Gateway(url, "k", Duration.ofSeconds(1), read)), new ObjectMapper());
    }

    private static void withStatus(int status, java.util.function.Consumer<String> body) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> { ex.sendResponseHeaders(status, -1); ex.close(); });
        s.start();
        try { body.accept("http://127.0.0.1:" + s.getAddress().getPort()); } finally { s.stop(0); }
    }

    @Test void http429IsRetryable() throws IOException {
        withStatus(429, url -> assertThatThrownBy(() -> clientFor(url, Duration.ofSeconds(5)).complete(REQ))
                .isInstanceOfSatisfying(LlmCallException.class, e -> assertThat(e.retryable()).isTrue()));
    }

    @Test void http401IsPermanent() throws IOException {
        withStatus(401, url -> assertThatThrownBy(() -> clientFor(url, Duration.ofSeconds(5)).complete(REQ))
                .isInstanceOfSatisfying(LlmCallException.class, e -> assertThat(e.retryable()).isFalse()));
    }

    @Test void connectionRefusedIsRetryable() {
        assertThatThrownBy(() -> clientFor("http://127.0.0.1:1", Duration.ofSeconds(5)).complete(REQ))
                .isInstanceOfSatisfying(LlmCallException.class, e -> assertThat(e.retryable()).isTrue());
    }
}
