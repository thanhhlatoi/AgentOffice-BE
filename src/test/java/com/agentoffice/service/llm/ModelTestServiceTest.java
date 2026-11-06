package com.agentoffice.service.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentoffice.client.llm.LlmRequest;
import com.agentoffice.client.llm.LlmResponse;
import com.agentoffice.domain.usage.BudgetExceededException;
import com.agentoffice.domain.usage.BudgetScope;
import com.agentoffice.domain.usage.CallContext;
import com.agentoffice.domain.usage.LlmCallException;
import com.agentoffice.dto.llm.ModelTestResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelTestServiceTest {
    private final MeteredLlmClient llm = mock(MeteredLlmClient.class);
    private final ModelTestService service = new ModelTestService(llm);

    @Test void successReportsTokensAndUsesModelTestPurpose() {
        when(llm.complete(any())).thenAnswer(i -> {
            LlmRequest req = i.getArgument(0);
            assertThat(req.context().purpose()).isEqualTo(CallContext.Purpose.MODEL_TEST);
            return new LlmResponse("pong", 12, 3, req.model());
        });
        ModelTestResponse r = service.test("writer");
        assertThat(r.ok()).isTrue();
        assertThat(r.inputTokens()).isEqualTo(12);
        assertThat(r.outputTokens()).isEqualTo(3);
        assertThat(r.error()).isNull();
    }

    @Test void gatewayFailureIsReportedNotThrown() {
        when(llm.complete(any())).thenThrow(new LlmCallException("HTTP 401", false));
        ModelTestResponse r = service.test("writer");
        assertThat(r.ok()).isFalse();
        assertThat(r.error()).isEqualTo("HTTP 401");
    }

    @Test void unclassifiedErrorIsReportedWithoutMessage() {
        when(llm.complete(any())).thenThrow(new UnsupportedOperationException("chứa dữ liệu nhạy cảm"));
        assertThat(service.test("writer").error()).doesNotContain("nhạy cảm").contains("UnsupportedOperationException");
    }

    @Test void budgetExhaustedPropagates() {
        when(llm.complete(any())).thenThrow(new BudgetExceededException(UUID.randomUUID(), BudgetScope.GLOBAL, null, 5));
        assertThatThrownBy(() -> service.test("writer")).isInstanceOf(BudgetExceededException.class);
    }
}
