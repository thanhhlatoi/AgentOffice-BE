package com.agentoffice.dto.project;

import com.agentoffice.domain.project.ProjectStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Tiền là số nguyên micro-USD. */
public final class ProjectDto {
    private ProjectDto() {}

    public record View(UUID id, String name, String description, ProjectStatus status, UUID departmentId, UUID ownerAgentId,
                       String color, Instant deadline, int progressPct, boolean hasPlan, Instant createdAt, Instant finishedAt) {}

    public record GoalRow(UUID id, String title, String status, int progressPct, long taskCount) {}

    /** View + số liệu tính tại thời điểm đọc. */
    public record Detail(View project, Map<String, Long> tasksByStatus, long failedTasks, long waitingApprovalTasks,
                         long costMicroUsd, Long budgetMicroUsd, String budgetPeriod, Long deadlineRemainingSec, boolean overdue,
                         List<GoalRow> goals) {}

    public record Create(@NotBlank @Size(max = 200) String name, @Size(max = 5000) String description, UUID departmentId,
                         UUID ownerAgentId, @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color, Instant deadline) {}

    /** status chỉ nhận ACTIVE|PAUSED (DONE do hệ thống, ARCHIVED qua /archive). */
    public record Patch(@Size(min = 1, max = 200) String name, @Size(max = 5000) String description, ProjectStatus status,
                        UUID departmentId, UUID ownerAgentId, @Pattern(regexp = "#[0-9A-Fa-f]{6}") String color, Instant deadline) {}

    public record MoveGoal(UUID projectId) {}

    public record HistoryPoint(LocalDate day, int progressPct, int succeeded, int failed, int total) {}
}
