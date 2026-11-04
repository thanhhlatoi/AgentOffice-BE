package com.agentoffice.client.llm;

/**
 * Nhà cung cấp LLM thô. KHÔNG ai ngoài client.llm và service.llm được dùng kiểu này:
 * mọi nơi khác gọi MeteredLlmClient (ArchitectureTest canh).
 */
public interface LlmClient {
    LlmResponse complete(LlmRequest request);

    /** Tên nhà cung cấp, khớp model_price.provider. */
    default String provider() { return "unknown"; }
}
