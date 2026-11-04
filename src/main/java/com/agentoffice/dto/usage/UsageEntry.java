package com.agentoffice.dto.usage;

import java.time.Instant;
import java.util.UUID;

/** Một dòng usage sắp ghi. KHÔNG có nội dung prompt/response (thuộc BE-7). */
public record UsageEntry(UUID requestId, Instant occurredAt, UUID agentId, UUID taskId, UUID goalId, UUID projectId,
                         UUID departmentId, String provider, String model, String purpose, long promptTokens,
                         long completionTokens, long cachedTokens, boolean estimated, long costMicroUsd, UUID priceId,
                         boolean priced, long latencyMs, String status, String errorCode) {
    public boolean isError() { return "ERROR".equals(status); }
}
