package com.agentoffice.dto.fe;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Hợp đồng JSON của FE (D:\FE\AI-Company-Office\src\types\index.ts). Tên trường và giá trị enum phải khớp zod bên FE,
 * nên khác với DTO "gốc" của BE (vd status DONE thay cho SUCCEEDED). Id luôn là chuỗi UUID.
 */
public final class OfficeDto {
    private OfficeDto() {}

    public record CurrentTask(String id, String title) {}

    public record Agent(String id, String name, String role, String model, String systemPrompt, String color,
                        String status, CurrentTask currentTask, String departmentId, boolean isHead,
                        String runsOnDeviceId, List<String> skills) {}

    public record AgentInput(String name, String role, String systemPrompt, String model, String color,
                             String departmentId, List<String> skills) {}

    public record Department(String id, String name, String slug, String icon, String color, String headAgentId, int order,
                             List<String> props, int monitorsPerDesk) {}

    /** slug chỉ có tác dụng khi tạo (FE nhận biết phòng theo slug). headName/headRole chỉ dùng khi tạo. */
    public record DepartmentInput(String name, String slug, String icon, String color, List<String> props,
                                  Integer monitorsPerDesk, String headName, String headRole) {}

    public record Task(String id, String agentId, String title, String input, String status, String result,
                       Instant createdAt, Instant updatedAt, String goalId, String priority, List<String> dependsOn,
                       int attempts, String error, String assignedById, Instant startedAt, long tokensUsed,
                       long version, boolean locked, String parentId, String projectId, long costMicro) {}

    public record PlanItem(String key, String title, String assigneeId, String priority, List<String> dependsOn) {}

    public record Goal(String id, String title, String description, String departmentId, String projectId,
                       Long tokenBudget, long tokensUsed, boolean requirePlanApproval, String status,
                       List<PlanItem> plan, String rejectReason, Instant createdAt, Instant updatedAt, Instant finishedAt) {}

    public record GoalInput(String title, String description, String departmentId, Long tokenBudget, Boolean requirePlanApproval) {}

    public record Approval(String id, String agentId, String title, Instant createdAt, String riskType, String goalId,
                           String taskId, String action, Map<String, Object> argsSnapshot, Instant expiresAt,
                           String status, String decisionReason, Instant decidedAt) {}

    public record ApproveResult(String id, String status) {}

    public record SystemState(String state, String reason, long lastEventId) {}

    public record EventsPage(List<Object> events, boolean truncated, long lastId) {}

    public record TaskBody(String title, String input) {}

    public record ReasonBody(String reason) {}

    public record PriorityBody(String priority) {}
}
