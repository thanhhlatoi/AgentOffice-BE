package com.agentoffice.service.fe;

import com.agentoffice.config.ApprovalProperties;
import com.agentoffice.domain.approval.ApprovalKind;
import com.agentoffice.domain.approval.ApprovalStatus;
import com.agentoffice.domain.task.Plan;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.dto.fe.OfficeDto;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.entity.approval.Approval;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.entity.task.Task;
import com.agentoffice.entity.task.TaskDependency;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.repository.agent.AgentRepository;
import com.agentoffice.repository.approval.ApprovalRepository;
import com.agentoffice.repository.event.DomainEventRepository;
import com.agentoffice.repository.task.GoalRepository;
import com.agentoffice.repository.task.TaskDependencyRepository;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.service.system.SystemStateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đọc dữ liệu BE rồi đổi sang đúng hình dạng FE mong đợi (xem OfficeDto). Chỉ đọc; ghi đi qua các service nghiệp vụ có sẵn.
 * Dùng chung cho REST (/api/fe) và realtime (STOMP) để hai đường luôn trả cùng một dạng.
 */
@Service
@Transactional(readOnly = true)
public class ViewService {
    private static final Logger log = LoggerFactory.getLogger(ViewService.class);
    /** FE bắt buộc agentId của task: task chưa giao cho ai dùng UUID rỗng này. */
    public static final String NO_AGENT = "00000000-0000-0000-0000-000000000000";
    private static final String[] PALETTE = {"#2563eb", "#c93d31", "#16a34a", "#9333ea", "#ea580c", "#0891b2", "#ca8a04", "#db2777"};
    private static final String[] AGENT_STATUS = {"IDLE", "WORKING", "BLOCKED", "ERROR", "OFFLINE"};

    private final AgentRepository agents;
    private final TaskRepository tasks;
    private final TaskDependencyRepository deps;
    private final GoalRepository goals;
    private final ApprovalRepository approvals;
    private final DomainEventRepository events;
    private final SystemStateService system;
    private final ApprovalProperties approvalProps;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ViewService(AgentRepository agents, TaskRepository tasks, TaskDependencyRepository deps, GoalRepository goals,
                         ApprovalRepository approvals, DomainEventRepository events, SystemStateService system,
                         ApprovalProperties approvalProps, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.agents = agents;
        this.tasks = tasks;
        this.deps = deps;
        this.goals = goals;
        this.approvals = approvals;
        this.events = events;
        this.system = system;
        this.approvalProps = approvalProps;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ departments
    public List<OfficeDto.Department> departments() {
        List<OfficeDto.Department> out = new ArrayList<>();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, slug, icon, color, props, monitors_per_desk, sort_order, head_agent_id FROM department ORDER BY sort_order, name");
        for (Map<String, Object> r : rows) {
            String name = String.valueOf(r.get("name"));
            Object colorCol = r.get("color");
            Object head = r.get("head_agent_id");
            out.add(new OfficeDto.Department(String.valueOf(r.get("id")), name, String.valueOf(r.get("slug")), String.valueOf(r.get("icon")),
                    colorCol != null ? colorCol.toString() : color(name), head == null ? null : head.toString(),
                    ((Number) r.get("sort_order")).intValue() - 1, textArray(r.get("props")),
                    ((Number) r.get("monitors_per_desk")).intValue()));
        }
        return out;
    }

    private static List<String> textArray(Object sqlArray) {
        if (sqlArray == null) return List.of();
        try {
            Object[] a = (Object[]) ((java.sql.Array) sqlArray).getArray();
            List<String> out = new ArrayList<>();
            for (Object o : a) out.add(String.valueOf(o));
            return out;
        } catch (java.sql.SQLException e) {
            return List.of();
        }
    }

    private java.util.Set<UUID> headIds() {
        return new java.util.HashSet<>(jdbc.queryForList("SELECT head_agent_id FROM department WHERE head_agent_id IS NOT NULL", UUID.class));
    }

    // ------------------------------------------------------------------ agents
    public List<OfficeDto.Agent> agents() {
        Map<UUID, Task> running = byAssignee(tasks.findByStatus(TaskStatus.RUNNING));
        Map<UUID, Task> waiting = byAssignee(tasks.findByStatus(TaskStatus.WAITING_APPROVAL));
        java.util.Set<UUID> heads = headIds();
        return agents.findByArchivedAtIsNull(Sort.by("createdAt")).stream().map(a -> agent(a, running.get(a.getId()), waiting.get(a.getId()), heads)).toList();
    }

    public OfficeDto.Agent agent(UUID id) {
        Agent a = agents.findById(id).filter(x -> x.getArchivedAt() == null).orElseThrow(() -> new NotFoundException("agent", id));
        return agent(a, firstOf(id, TaskStatus.RUNNING), firstOf(id, TaskStatus.WAITING_APPROVAL), headIds());
    }

    private OfficeDto.Agent agent(Agent a, Task running, Task waiting, java.util.Set<UUID> heads) {
        String status = validStatus(a.getStatus());
        OfficeDto.CurrentTask current = null;
        if (running != null) { status = "WORKING"; current = new OfficeDto.CurrentTask(running.getId().toString(), running.getTitle()); }
        else if (waiting != null) { status = "BLOCKED"; current = new OfficeDto.CurrentTask(waiting.getId().toString(), waiting.getTitle()); }
        String color = a.getColor() != null ? a.getColor() : color(a.getName());
        String model = a.getModelProfile() != null ? a.getModelProfile() : "default";
        return new OfficeDto.Agent(a.getId().toString(), a.getName(), a.getRole(), model,
                a.getSystemPrompt() == null ? "" : a.getSystemPrompt(), color, status, current,
                a.getDepartmentId() == null ? null : a.getDepartmentId().toString(), heads.contains(a.getId()), null,
                a.getSkills() == null ? List.of() : a.getSkills());
    }

    private Task firstOf(UUID agentId, TaskStatus s) {
        return tasks.findByAssigneeAgentIdOrderByCreatedAtDesc(agentId).stream().filter(t -> t.getStatus() == s).findFirst().orElse(null);
    }

    private static Map<UUID, Task> byAssignee(List<Task> list) {
        Map<UUID, Task> m = new HashMap<>();
        for (Task t : list) if (t.getAssigneeAgentId() != null) m.putIfAbsent(t.getAssigneeAgentId(), t);
        return m;
    }

    private static String validStatus(String s) {
        for (String v : AGENT_STATUS) if (v.equals(s)) return v;
        return "IDLE";
    }

    // ------------------------------------------------------------------ tasks
    public List<OfficeDto.Task> tasks() { return mapTasks(tasks.findTop500ByOrderByCreatedAtDesc()); }

    public List<OfficeDto.Task> tasksOfAgent(UUID agentId) { return mapTasks(tasks.findByAssigneeAgentIdOrderByCreatedAtDesc(agentId)); }

    public OfficeDto.Task task(UUID id) {
        return mapTasks(List.of(tasks.findById(id).orElseThrow(() -> new NotFoundException("task", id)))).get(0);
    }

    /** Giữ đúng thứ tự `ids` (kanban đã xếp sẵn). */
    public List<OfficeDto.Task> tasksByIds(List<UUID> ids) {
        Map<UUID, Task> by = new HashMap<>();
        for (Task t : tasks.findAllById(ids)) by.put(t.getId(), t);
        return mapTasks(ids.stream().map(by::get).filter(java.util.Objects::nonNull).toList());
    }

    private List<OfficeDto.Task> mapTasks(Collection<Task> list) {
        if (list.isEmpty()) return List.of();
        List<UUID> ids = list.stream().map(Task::getId).toList();
        Map<UUID, List<String>> depMap = new HashMap<>();
        for (TaskDependency d : deps.findByTaskIds(ids))
            depMap.computeIfAbsent(d.getTaskId(), k -> new ArrayList<>()).add(d.getDependsOnTaskId().toString());
        Map<UUID, long[]> cost = new HashMap<>();
        jdbc.query("SELECT task_id, coalesce(sum(cost_micro_usd),0), coalesce(sum(prompt_tokens + completion_tokens),0) FROM usage_record WHERE task_id = ANY(?) GROUP BY task_id",
                rs -> { cost.put(rs.getObject(1, UUID.class), new long[] {rs.getLong(2), rs.getLong(3)}); }, (Object) ids.toArray(new UUID[0]));
        return list.stream().map(t -> task(t, depMap.getOrDefault(t.getId(), List.of()), cost.getOrDefault(t.getId(), new long[2]))).toList();
    }

    private OfficeDto.Task task(Task t, List<String> dependsOn, long[] cost) {
        return new OfficeDto.Task(t.getId().toString(), t.getAssigneeAgentId() == null ? NO_AGENT : t.getAssigneeAgentId().toString(),
                t.getTitle(), t.getInput() == null ? "" : t.getInput(), taskStatus(t.getStatus()), t.getResult(),
                t.getCreatedAt(), t.getUpdatedAt(), idOrNull(t.getGoalId()), t.getPriority().name(), dependsOn,
                t.getAttempts(), t.getError(), uuidOrNull(t.getCreatedBy()), t.getStartedAt(), cost[1], t.getVersion(),
                t.getStatus() == TaskStatus.WAITING_APPROVAL, idOrNull(t.getParentTaskId()), idOrNull(t.getProjectId()), cost[0]);
    }

    static String taskStatus(TaskStatus s) {
        return switch (s) {
            case PENDING, BLOCKED, QUEUED -> "QUEUED";
            case RUNNING -> "RUNNING";
            case WAITING_APPROVAL -> "AWAITING_APPROVAL";
            case WAITING_BUDGET -> "WAITING_BUDGET";
            case SUCCEEDED -> "DONE";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
        };
    }

    // ------------------------------------------------------------------ goals
    public List<OfficeDto.Goal> goals() { return goals.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream().map(this::goal).toList(); }

    public OfficeDto.Goal goal(UUID id) { return goal(goals.findById(id).orElseThrow(() -> new NotFoundException("goal", id))); }

    private OfficeDto.Goal goal(Goal g) {
        String status = switch (g.getStatus()) {
            case DRAFT, PLANNING -> "PLANNING";
            case AWAITING_PLAN_APPROVAL -> "AWAITING_PLAN_APPROVAL";
            case RUNNING -> "RUNNING";
            case SUCCEEDED -> "DONE";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
        };
        return new OfficeDto.Goal(g.getId().toString(), g.getTitle(), g.getDescription() == null ? "" : g.getDescription(),
                idOrNull(g.getDepartmentId()), idOrNull(g.getProjectId()), g.getTokenBudget(), g.getTokensUsed(),
                approvalProps.requirePlanApproval(), status, plan(g.getPlan()), null, g.getCreatedAt(),
                g.getFinishedAt() != null ? g.getFinishedAt() : g.getCreatedAt(), g.getFinishedAt());
    }

    private List<OfficeDto.PlanItem> plan(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, Plan.class).tasks().stream()
                    .map(t -> new OfficeDto.PlanItem(t.key(), t.title(), t.assigneeAgentId() == null ? NO_AGENT : t.assigneeAgentId().toString(),
                            t.priority().name(), t.dependsOnKeys()))
                    .toList();
        } catch (Exception e) {
            log.warn("Không đọc được plan của goal: {}", e.getMessage());
            return List.of();
        }
    }

    // ------------------------------------------------------------------ approvals
    public List<OfficeDto.Approval> approvals(String status) {
        List<Approval> list = new ArrayList<>(approvals.findByStatusOrderByCreatedAtAsc(ApprovalStatus.PENDING));
        if ("ALL".equalsIgnoreCase(status)) list.addAll(approvals.findTop50ByStatusNotOrderByCreatedAtDesc(ApprovalStatus.PENDING));
        else if (!"PENDING".equalsIgnoreCase(status)) {
            ApprovalStatus s = ApprovalStatus.valueOf(status.toUpperCase(Locale.ROOT));
            list = new ArrayList<>(approvals.findByStatusOrderByCreatedAtAsc(s));
        }
        return list.stream().map(this::approval).toList();
    }

    public OfficeDto.Approval approval(UUID id) {
        return approval(approvals.findById(id).orElseThrow(() -> new NotFoundException("approval", id)));
    }

    private OfficeDto.Approval approval(Approval a) {
        boolean plan = a.getKind() == ApprovalKind.PLAN;
        String risk = a.getRiskLevel() == null ? "EXTERNAL" : switch (a.getRiskLevel()) {
            case MONEY -> "MONEY";
            case DESTRUCTIVE -> "DESTRUCTIVE";
            default -> "EXTERNAL";
        };
        return new OfficeDto.Approval(a.getId().toString(), a.getAgentId() == null ? NO_AGENT : a.getAgentId().toString(),
                a.getPreview(), a.getCreatedAt(), risk, idOrNull(a.getGoalId()), idOrNull(a.getTaskId()),
                plan ? "goal.plan.approve" : a.getToolName(), args(a.getArgsSnapshot()), a.getExpiresAt(),
                a.getStatus().name(), a.getReason(), a.getDecidedAt());
    }

    private Map<String, Object> args(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    // ------------------------------------------------------------------ system / events
    public OfficeDto.SystemState systemState() {
        return new OfficeDto.SystemState(system.isPaused() ? "PAUSED" : "RUNNING", null, events.maxId());
    }

    /** Realtime không gắn id sự kiện (id bị hở khi transaction rollback) nên không có gì để "bù": luôn báo truncated để FE tải lại. */
    public OfficeDto.EventsPage eventsAfter(long afterId) {
        long last = events.maxId();
        return new OfficeDto.EventsPage(List.of(), afterId < last, last);
    }

    // ------------------------------------------------------------------ helpers
    private static String idOrNull(UUID id) { return id == null ? null : id.toString(); }

    private static String uuidOrNull(String s) {
        if (s == null) return null;
        try { return UUID.fromString(s).toString(); } catch (IllegalArgumentException e) { return null; }
    }

    static String slug(String name) { return com.agentoffice.util.Slugs.of(name, "phong"); }


    private static String color(String key) { return PALETTE[Math.floorMod(key.hashCode(), PALETTE.length)]; }

}
