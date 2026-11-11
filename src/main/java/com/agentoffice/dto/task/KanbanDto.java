package com.agentoffice.dto.task;

import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.TaskStatus;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Tiền là số nguyên micro-USD. Tên cột viết thường: waiting|running|review|done|closed. */
public final class KanbanDto {
    private KanbanDto() {}

    public record Card(UUID id, String title, UUID assigneeAgentId, String assigneeName, Priority priority, TaskStatus status,
                       UUID projectId, String projectColor, UUID goalId, String goalTitle, Integer progressPct,
                       long costMicroUsd, long ageSec, String lockedReason, long version, UUID retryOfTaskId,
                       java.time.Instant createdAt, java.time.Instant updatedAt) {}

    public record Column(String column, long total, List<Card> cards, String nextCursor) {}

    public record Board(List<Column> columns) {}

    /** beforeTaskId = thẻ sẽ nằm NGAY SAU thẻ được kéo; afterTaskId = thẻ nằm NGAY TRƯỚC nó (chỉ dùng khi sắp xếp/chạy lại). */
    public record MoveRequest(@NotNull String toColumn, UUID beforeTaskId, UUID afterTaskId, @NotNull Long expectedVersion) {}

    /** task: thẻ sau lệnh; với lệnh chạy lại (done|failed -> waiting) đó là task MỚI. */
    public record MoveResult(String action, TaskDto task) {}

    public record AssignRequest(@NotNull UUID agentId, @NotNull Long expectedVersion) {}
}
