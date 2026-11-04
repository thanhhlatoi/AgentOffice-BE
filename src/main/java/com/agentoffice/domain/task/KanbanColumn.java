package com.agentoffice.domain.task;

import java.util.List;

/** 5 cột của bảng Kanban và các trạng thái task thuộc từng cột. */
public enum KanbanColumn {
    WAITING(TaskStatus.PENDING, TaskStatus.BLOCKED, TaskStatus.QUEUED),
    RUNNING(TaskStatus.RUNNING),
    /** Chờ con người/ngân sách; thẻ mang huy hiệu khác nhau theo trạng thái gốc. */
    REVIEW(TaskStatus.WAITING_APPROVAL, TaskStatus.WAITING_BUDGET),
    DONE(TaskStatus.SUCCEEDED),
    CLOSED(TaskStatus.FAILED, TaskStatus.CANCELLED);

    private final List<TaskStatus> statuses;

    KanbanColumn(TaskStatus... statuses) { this.statuses = List.of(statuses); }

    public List<TaskStatus> statuses() { return statuses; }

    public static KanbanColumn of(TaskStatus s) {
        for (KanbanColumn c : values()) if (c.statuses.contains(s)) return c;
        throw new IllegalArgumentException(s.name());
    }
}
