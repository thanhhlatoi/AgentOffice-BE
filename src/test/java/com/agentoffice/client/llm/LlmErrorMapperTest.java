package com.agentoffice.client.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentoffice.domain.usage.LlmCallException;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Spec cho LÕI của bạn (LlmErrorMapper). ĐỎ cho tới khi viết xong. */
@Tag("core")
class LlmErrorMapperTest {
    @ParameterizedTest @ValueSource(ints = {429, 500, 502, 503, 504})
    void rateLimitAndServerErrorsAreRetryable(int status) {
        assertThat(LlmErrorMapper.fromStatus(status).retryable()).as("HTTP %d", status).isTrue();
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 404})
    void clientErrorsArePermanent(int status) {
        assertThat(LlmErrorMapper.fromStatus(status).retryable()).as("HTTP %d", status).isFalse();
    }

    @Test void messageNamesStatusOnly() {
        assertThat(LlmErrorMapper.fromStatus(401).getMessage()).contains("401");
    }

    @Test void timeoutAndNetworkErrorsAreRetryableAndKeepCause() {
        for (IOException e : new IOException[] {new HttpTimeoutException("timed out"), new IOException("connection reset")}) {
            LlmCallException ex = LlmErrorMapper.fromTransport(e);
            assertThat(ex.retryable()).isTrue();
            assertThat(ex).hasCause(e);
        }
    }
}
