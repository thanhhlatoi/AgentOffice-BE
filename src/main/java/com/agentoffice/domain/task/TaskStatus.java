package com.agentoffice.domain.task;

public enum TaskStatus {
    PENDING, BLOCKED, QUEUED, RUNNING, WAITING_APPROVAL,
    /** Đã có trong enum, chưa dùng ở BE-1..3 (BudgetGuard sẽ dùng). */
    WAITING_BUDGET,
    SUCCEEDED, FAILED, CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
