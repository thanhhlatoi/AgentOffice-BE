package com.agentoffice.domain.task;

public enum GoalStatus {
    DRAFT, PLANNING, AWAITING_PLAN_APPROVAL, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
