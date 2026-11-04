package com.agentoffice.domain.event;

/** Tên sự kiện (prompt BE-1..3, mục 9). */
public final class EventTypes {
    private EventTypes() {}
    public static final String GOAL_CREATED = "goal.created";
    public static final String GOAL_PLAN_PROPOSED = "goal.plan_proposed";
    public static final String GOAL_SUCCEEDED = "goal.succeeded";
    public static final String GOAL_FAILED = "goal.failed";
    public static final String GOAL_CANCELLED = "goal.cancelled";
    public static final String TASK_CREATED = "task.created";
    public static final String TASK_STATE_CHANGED = "task.state_changed";
    public static final String TASK_SUCCEEDED = "task.succeeded";
    public static final String TASK_FAILED = "task.failed";
    public static final String TASK_CANCELLED = "task.cancelled";
    public static final String TASK_HANDOFF = "task.handoff";
    public static final String APPROVAL_REQUESTED = "approval.requested";
    public static final String APPROVAL_RESOLVED = "approval.resolved";
    public static final String USAGE_UNPRICED = "usage.unpriced";
    public static final String BUDGET_WARNING = "budget.warning";
    public static final String BUDGET_EXCEEDED = "budget.exceeded";
    public static final String BUDGET_RESUMED = "budget.resumed";
    public static final String PROJECT_CREATED = "project.created";
    public static final String PROJECT_UPDATED = "project.updated";
    public static final String PROJECT_PROGRESS_CHANGED = "project.progress_changed";
    public static final String TASK_MOVED = "task.moved";
    public static final String SYSTEM_PAUSED = "system.paused";
    public static final String SYSTEM_RESUMED = "system.resumed";
}
