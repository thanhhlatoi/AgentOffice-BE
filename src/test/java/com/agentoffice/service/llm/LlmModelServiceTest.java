package com.agentoffice.service.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.client.llm.LlmClient;
import com.agentoffice.client.llm.LlmModel;
import com.agentoffice.client.llm.LlmRequest;
import com.agentoffice.client.llm.LlmResponse;
import com.agentoffice.domain.usage.LlmCallException;
import com.agentoffice.dto.llm.LlmProviderDto;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmModelServiceTest {
    private static LlmClient stub(List<LlmModel> models) {
        return new LlmClient() {
            @Override public LlmResponse complete(LlmRequest r) { throw new UnsupportedOperationException(); }
            @Override public List<LlmModel> models() { return models; }
        };
    }

    private final LlmModelService service = new LlmModelService(stub(List.of(
            new LlmModel("cx/a", "cx"), new LlmModel("ollama/x", "ollama"), new LlmModel("cx/b", "cx"), new LlmModel("combo", null))));

    @Test void providersAreGroupedWithCountsInGatewayOrder() {
        assertThat(service.providers()).containsExactly(
                new LlmProviderDto("cx", 2), new LlmProviderDto("ollama", 1), new LlmProviderDto("unknown", 1));
    }

    @Test void modelsFilterByProvider() {
        assertThat(service.models("cx")).extracting("id").containsExactly("cx/a", "cx/b");
        assertThat(service.models("unknown")).extracting("id").containsExactly("combo");
        assertThat(service.models("nope")).isEmpty();
    }

    @Test void gatewayFailureBecomesBadGateway() {
        LlmClient failing = new LlmClient() {
            @Override public LlmResponse complete(LlmRequest r) { throw new UnsupportedOperationException(); }
            @Override public List<LlmModel> models() { throw new LlmCallException("HTTP 503", true); }
        };
        assertThatThrownBy(() -> new LlmModelService(failing).providers())
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.LLM_GATEWAY_ERROR));
    }
}
