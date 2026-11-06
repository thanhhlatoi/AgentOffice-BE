package com.agentoffice.dto.fe;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Hợp đồng JSON FE cho nhóm kanban / dự án / chi phí (D:\FE\AI-Company-Office\src\types\{kanban,project,cost}.ts). Tiền = micro-USD. */
public final class BizDto {
    private BizDto() {}

    public record Page<T>(List<T> items, long total, String nextCursor) {}

    public record History(Instant at, String status, String note) {}

    public record Counts(long queued, long running, long awaitingApproval, long waitingBudget, long done, long failed, long cancelled) {}

    public record Project(String id, String name, String description, String departmentId, String color, Instant dueDate, String status,
                          Long budgetMicro, long costMicro, int progressPct, Counts counts, List<String> goalIds, Instant createdAt) {}

    public record ProjectInput(String name, String description, String departmentId, String color, String dueDate, Long budgetMicro) {}

    public record Snapshot(String day, int progressPct) {}

    public record Activity(String id, Instant at, String message, String kind) {}

    public record MoveBody(String toColumn, String beforeTaskId, Long expectedVersion) {}

    public record AssignBody(String agentId) {}

    public record ProjectBody(String projectId) {}

    public record UsageRow(String key, String label, long totalMicro, long calls, long inputTokens, long outputTokens) {}

    public record UsageDay(String day, long totalMicro, Map<String, Long> parts) {}

    public record Usage(String from, String to, String groupBy, long totalMicro, Long prevTotalMicro, long calls, long inputTokens,
                        long outputTokens, Long avgPerTaskMicro, long unpricedCalls, List<UsageDay> byDay, List<UsageRow> rows) {}

    public record ModelPrice(String id, String model, long inputPerMTokMicro, long outputPerMTokMicro, Instant effectiveFrom) {}

    public record ModelPriceInput(String model, Long inputPerMTokMicro, Long outputPerMTokMicro, String effectiveFrom) {}

    public record Budget(String id, String scopeType, String scopeId, String scopeLabel, String period, long limitMicro, long usedMicro,
                         long reservedMicro, long waitingTasks, int warnPct, String mode, String state) {}

    public record BudgetInput(String scopeType, String scopeId, String period, Long limitMicro, Integer warnPct, String mode) {}

    public record BudgetUpdate(Long limitMicro, Integer warnPct, String mode, String reason) {}

    public record BudgetChange(String id, String budgetId, Instant at, long fromMicro, long toMicro, String reason) {}

    public record DeptStats(long doneToday, Double tokenCostToday) {}

    // ---- thông báo / hội thoại
    public record Target(String kind, String id) {}

    public record Notification(String id, String level, String type, String title, String body, Target target, Instant createdAt, Instant readAt) {}

    public record Count(long count) {}

    public record Conversation(String id, String title, String agentId, String goalId, String taskId, String projectId, Instant startedAt,
                               Instant updatedAt, long messageCount, long tokens, long costMicro, boolean hasError, List<String> tools, String snippet) {}

    public record Message(String id, String role, String content, Instant createdAt, long tokens, long costMicro, String toolName,
                          Map<String, Object> toolArgs, boolean truncated) {}

    // ---- công cụ và quyền
    public record ToolView(String id, String name, String group, String risk, String description, Map<String, Object> scopeSchema, boolean readsExternal,
                           boolean readsPrivate, boolean sendsOut, boolean enabled, boolean requiresAdminFlag, boolean adminFlagOn, long agentCount) {}

    public record GrantView(String id, String agentId, String toolId, Map<String, Object> scope, Map<String, Object> limits, boolean alwaysApprove,
                            String secretId, Instant createdAt, Instant updatedAt) {}

    public record GrantInput(String toolId, Map<String, Object> scope, Map<String, Object> limits, Boolean alwaysApprove, String secretId) {}

    public record PermissionChange(List<GrantInput> grants, List<String> remove, String reason) {}

    public record EffectiveTool(String toolId, boolean allowed, String risk, boolean needsApproval, String scopeSummary, String expiresAt, String blockedReason) {}

    public record Effective(String agentId, List<EffectiveTool> tools, boolean privateData, boolean readsExternal, boolean sendsOut) {}

    public record CheckBody(String agentId, Map<String, Object> args) {}

    public record CheckResult(String decision, String reason) {}

    public record ToolPatch(Boolean enabled, Boolean adminFlagOn, String reason) {}

    public record ToolCall(String id, Instant at, String agentId, String toolId, String decision, String reason, String taskId, Map<String, Object> args, boolean tainted) {}

    public record SecretView(String id, String name, String kind, Instant createdAt, Instant rotatedAt, Instant lastUsedAt, List<Map<String, String>> usedBy) {}

    public record AuditEntry(String id, Instant at, String actor, String agentId, String toolId, String action, Map<String, Object> before, Map<String, Object> after, String reason) {}
}
