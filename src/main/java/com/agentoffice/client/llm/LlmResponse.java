package com.agentoffice.client.llm;

/** inputTokens GỒM cả cachedTokens. inputTokens và outputTokens cùng 0 = nhà cung cấp không báo; MeteredLlmClient sẽ ước lượng. */
public record LlmResponse(String text, long inputTokens, long outputTokens, String model, long cachedTokens) {
    public LlmResponse(String text, long inputTokens, long outputTokens, String model) {
        this(text, inputTokens, outputTokens, model, 0);
    }
}
