package com.agentoffice.domain.usage;

/**
 * Cuộc gọi LLM thất bại. retryable=true (429/5xx/timeout/mạng): AgentRunner trả RETRYABLE_FAILURE để dispatcher thử lại;
 * false (400/401/403/404...): lỗi vĩnh viễn, AgentRunner trả FAILED. Thông điệp KHÔNG được chứa prompt hay api-key.
 */
public class LlmCallException extends RuntimeException {
    private final boolean retryable;

    public LlmCallException(String message, boolean retryable) { this(message, retryable, null); }

    public LlmCallException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() { return retryable; }
}
