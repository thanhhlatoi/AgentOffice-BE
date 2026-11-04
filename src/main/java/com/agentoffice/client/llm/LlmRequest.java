package com.agentoffice.client.llm;

import com.agentoffice.domain.usage.CallContext;
import java.util.List;
import java.util.Objects;

/** maxOutputTokens BẮT BUỘC > 0: BudgetGuard ước tính chi phí tối đa từ con số này. */
public record LlmRequest(String model, List<Msg> messages, int maxOutputTokens, CallContext context) {
    public record Msg(String role, String content) {}

    public LlmRequest {
        Objects.requireNonNull(context, "context (CallContext) bắt buộc");
        if (maxOutputTokens <= 0) throw new IllegalArgumentException("maxOutputTokens phải > 0");
    }
}
