package com.agentoffice.dto.usage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Mọi số tiền là số nguyên micro-USD (1 USD = 1_000_000). Không có số thực. */
public final class UsageDto {
    private UsageDto() {}

    public record SummaryRow(String key, long calls, long errors, long promptTokens, long completionTokens,
                             long costMicroUsd, long unpricedCalls) {}

    public record RecordRow(UUID id, UUID requestId, Instant occurredAt, UUID agentId, UUID taskId, UUID goalId,
                            UUID projectId, String provider, String model, String purpose, long promptTokens,
                            long completionTokens, long cachedTokens, boolean estimated, long costMicroUsd,
                            boolean priced, long latencyMs, String status, String errorCode) {}

    public record RecordPage(List<RecordRow> items, String nextCursor) {}

    public record UnpricedRow(String provider, String model, long calls, Instant firstSeen, Instant lastSeen) {}
}
