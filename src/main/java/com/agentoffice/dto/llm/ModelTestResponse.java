package com.agentoffice.dto.llm;

/** ok=false: gọi thử thất bại, error là lý do (không chứa prompt/key). Token 0 nếu lỗi hoặc gateway không báo. */
public record ModelTestResponse(String model, boolean ok, long latencyMs, long inputTokens, long outputTokens, String error) {}
