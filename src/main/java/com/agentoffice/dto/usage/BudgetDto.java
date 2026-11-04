package com.agentoffice.dto.usage;

import com.agentoffice.domain.usage.BudgetPeriodType;
import com.agentoffice.domain.usage.BudgetScope;
import com.agentoffice.domain.usage.HardAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Tiền là số nguyên micro-USD. */
public final class BudgetDto {
    private BudgetDto() {}

    public record View(UUID id, BudgetScope scopeType, UUID scopeId, BudgetPeriodType period, long limitMicroUsd,
                       Long limitTokens, int softPct, HardAction hardAction, boolean enabled, Instant createdAt) {}

    public record Create(@NotNull BudgetScope scopeType, UUID scopeId, @NotNull BudgetPeriodType period,
                         @NotNull @Positive Long limitMicroUsd, @Positive Long limitTokens,
                         @Min(1) @Max(100) Integer softPct, HardAction hardAction) {}

    /** Không có limit: chỉ đổi hạn mức qua POST /raise (có lý do + lịch sử). */
    public record Patch(@Min(1) @Max(100) Integer softPct, HardAction hardAction, Boolean enabled, @Positive Long limitTokens) {}

    public record Raise(@NotNull @Positive Long newLimit, @NotBlank @Size(max = 500) String reason) {}

    public record Status(UUID budgetId, BudgetPeriodType period, LocalDate periodStart, long limitMicroUsd, long spentMicroUsd,
                         long reservedMicroUsd, long remainingMicroUsd, long usedPct, long spentTokens,
                         boolean softAlerted, boolean hardAlerted) {}

    public record Change(UUID id, long oldLimitMicroUsd, long newLimitMicroUsd, String reason, String changedBy, Instant changedAt) {}
}
