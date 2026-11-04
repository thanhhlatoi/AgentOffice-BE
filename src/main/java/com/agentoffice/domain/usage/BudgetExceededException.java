package com.agentoffice.domain.usage;

import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import java.util.Map;
import java.util.UUID;

/**
 * Ném khi một ngân sách BLOCK không còn đủ chỗ cho cuộc gọi LLM. Runner KHÔNG bắt nó: TaskDispatcher đưa task sang
 * WAITING_BUDGET (không FAILED, không tính là lần thử lại).
 */
public class BudgetExceededException extends AppException {
    private final UUID budgetId;
    private final BudgetScope scopeType;
    private final UUID scopeId;
    private final long neededMicroUsd;

    public BudgetExceededException(UUID budgetId, BudgetScope scopeType, UUID scopeId, long neededMicroUsd) {
        super(ErrorCode.BUDGET_EXCEEDED, "Vượt ngân sách " + scopeType + (scopeId == null ? "" : " " + scopeId)
                + " (cần thêm " + neededMicroUsd + " micro-USD)", Map.of("budgetId", String.valueOf(budgetId), "scopeType", scopeType.name()));
        this.budgetId = budgetId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.neededMicroUsd = neededMicroUsd;
    }

    public UUID budgetId() { return budgetId; }
    public BudgetScope scopeType() { return scopeType; }
    public UUID scopeId() { return scopeId; }
    /** Phần ước tính đã bị từ chối: task chỉ chạy tiếp khi ngân sách còn ít nhất chừng này. */
    public long neededMicroUsd() { return neededMicroUsd; }
}
