package com.agentoffice.service.fe;

import com.agentoffice.config.UsageProperties;
import com.agentoffice.domain.task.KanbanColumn;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.usage.BudgetPeriodType;
import com.agentoffice.domain.usage.BudgetScope;
import com.agentoffice.domain.usage.HardAction;
import com.agentoffice.domain.project.ProjectStatus;
import com.agentoffice.dto.fe.BizDto;
import com.agentoffice.dto.fe.OfficeDto;
import com.agentoffice.dto.task.KanbanDto;
import com.agentoffice.dto.usage.BudgetDto;
import com.agentoffice.dto.usage.ModelPriceDto;
import com.agentoffice.dto.usage.UsageDto;
import com.agentoffice.dto.project.ProjectDto;
import com.agentoffice.entity.project.Project;
import com.agentoffice.entity.task.Task;
import com.agentoffice.entity.usage.Budget;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.service.project.ProjectService;
import com.agentoffice.service.task.KanbanService;
import com.agentoffice.service.usage.BudgetService;
import com.agentoffice.service.usage.PriceService;
import com.agentoffice.service.usage.UsageService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Kanban, dự án, chi phí cho FE: đọc/ghi qua các service nghiệp vụ có sẵn rồi đổi sang hình dạng zod của FE.
 * Không chứa quy tắc nghiệp vụ mới; không @Transactional (mỗi service con tự mở giao dịch).
 */
@Service
public class BizService {
    private static final String OWNER = "owner";
    private static final String NONE = "00000000-0000-0000-0000-000000000000";

    private final JdbcTemplate jdbc;
    private final ViewService view;
    private final KanbanService kanban;
    private final TaskRepository tasks;
    private final ProjectService projects;
    private final BudgetService budgets;
    private final PriceService prices;
    private final UsageService usage;
    private final UsageProperties usageProps;

    public BizService(JdbcTemplate jdbc, ViewService view, KanbanService kanban, TaskRepository tasks, ProjectService projects,
                        BudgetService budgets, PriceService prices, UsageService usage, UsageProperties usageProps) {
        this.jdbc = jdbc;
        this.view = view;
        this.kanban = kanban;
        this.tasks = tasks;
        this.projects = projects;
        this.budgets = budgets;
        this.prices = prices;
        this.usage = usage;
        this.usageProps = usageProps;
    }

    // ================================================================== kanban / task
    public BizDto.Page<OfficeDto.Task> kanbanPage(String column, UUID projectId, UUID agentId, UUID departmentId, String priority, String q,
                                                java.time.LocalDate from, java.time.LocalDate to, String cursor, int limit) {
        Priority p = null;
        if (priority != null && !priority.isBlank()) {
            try { p = Priority.valueOf(priority.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException e) { throw new AppException(ErrorCode.VALIDATION, "priority phải là HIGH|NORMAL|LOW"); }
        }
        KanbanDto.Column c = kanban.board(new KanbanService.Filter(projectId, null, agentId, departmentId, p, q, from, to), limit, column(column), cursor)
                .columns().get(0);
        return new BizDto.Page<>(view.tasksByIds(c.cards().stream().map(KanbanDto.Card::id).toList()), c.total(), c.nextCursor());
    }

    public OfficeDto.Task move(UUID id, BizDto.MoveBody b) {
        if (b.toColumn() == null || b.expectedVersion() == null) throw new AppException(ErrorCode.VALIDATION, "Cần toColumn và expectedVersion");
        KanbanColumn to = column(b.toColumn());
        UUID before = uuid(b.beforeTaskId());
        UUID after = null;
        if (before == null && to == KanbanColumn.WAITING) after = lastWaiting(id);      // FE: beforeTaskId null = thả xuống cuối cột
        KanbanDto.MoveResult r = kanban.move(id, to, before, after, b.expectedVersion());
        return view.task(r.task().id());
    }

    public OfficeDto.Task taskById(UUID id) { return view.task(id); }

    public OfficeDto.Task assign(UUID id, String agentId) {
        UUID agent = uuid(agentId);
        if (agent == null) throw new AppException(ErrorCode.VALIDATION, "Cần agentId");
        Task t = tasks.findById(id).orElseThrow(() -> new NotFoundException("task", id));
        kanban.assign(id, agent, t.getVersion());
        return view.task(id);
    }

    public List<BizDto.History> taskHistory(UUID id) {
        view.task(id);
        return jdbc.query("""
                SELECT created_at, type, coalesce(payload->>'to', payload->>'status', payload->>'toStatus') FROM domain_event
                WHERE aggregate_id = ? ORDER BY id""",
                (rs, i) -> new BizDto.History(rs.getTimestamp(1).toInstant(), historyStatus(rs.getString(2), rs.getString(3)), rs.getString(2)), id);
    }

    private static String historyStatus(String type, String to) {
        if (to != null) return ViewService.taskStatus(parseStatus(to));
        return switch (type) {
            case "task.created" -> "QUEUED";
            case "task.succeeded" -> "DONE";
            case "task.failed" -> "FAILED";
            case "task.cancelled" -> "CANCELLED";
            default -> "QUEUED";
        };
    }

    private static com.agentoffice.domain.task.TaskStatus parseStatus(String s) {
        try { return com.agentoffice.domain.task.TaskStatus.valueOf(s.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return com.agentoffice.domain.task.TaskStatus.QUEUED; }
    }

    private UUID lastWaiting(UUID self) {
        List<UUID> r = jdbc.query("""
                SELECT id FROM task WHERE status IN ('PENDING','BLOCKED','QUEUED') AND id <> ?
                ORDER BY CASE priority WHEN 'HIGH' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END DESC, kanban_rank DESC, id DESC LIMIT 1""",
                (rs, i) -> rs.getObject(1, UUID.class), self);
        return r.isEmpty() ? null : r.get(0);
    }

    static KanbanColumn column(String fe) {
        return switch (fe == null ? "" : fe.toUpperCase(Locale.ROOT)) {
            case "QUEUED" -> KanbanColumn.WAITING;
            case "RUNNING" -> KanbanColumn.RUNNING;
            case "AWAITING_APPROVAL" -> KanbanColumn.REVIEW;
            case "DONE" -> KanbanColumn.DONE;
            case "FAILED_CANCELLED" -> KanbanColumn.CLOSED;
            default -> throw new AppException(ErrorCode.VALIDATION, "Cột phải là QUEUED|RUNNING|AWAITING_APPROVAL|DONE|FAILED_CANCELLED");
        };
    }

    // ================================================================== dự án
    public List<BizDto.Project> projects(String status, String departmentId, String q) {
        List<Project> list = projects.list(null);
        Maps m = projectMaps();
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return list.stream()
                .filter(p -> status == null || status.isBlank() || feStatus(p).equalsIgnoreCase(status))
                .filter(p -> departmentId == null || departmentId.isBlank() || departmentId.equals(str(p.getDepartmentId())))
                .filter(p -> needle.isEmpty() || p.getName().toLowerCase(Locale.ROOT).contains(needle))
                .map(p -> project(p, m)).toList();
    }

    public BizDto.Project project(UUID id) { return project(projects.get(id), projectMaps()); }

    public BizDto.Project createProject(BizDto.ProjectInput in) {
        if (in.name() == null || in.name().isBlank()) throw new AppException(ErrorCode.VALIDATION, "Tên dự án là bắt buộc");
        Project p = projects.create(new ProjectDto.Create(in.name().trim(), in.description(), uuid(in.departmentId()), null,
                in.color() != null && in.color().matches("#[0-9A-Fa-f]{6}") ? in.color() : null, instant(in.dueDate())));
        if (in.budgetMicro() != null && in.budgetMicro() > 0)
            budgets.create(new BudgetDto.Create(BudgetScope.PROJECT, p.getId(), BudgetPeriodType.TOTAL, in.budgetMicro(), null, null, HardAction.BLOCK), OWNER);
        return project(p.getId());
    }

    public BizDto.Project archiveProject(UUID id) { projects.archive(id); return project(id); }

    public BizDto.Project reopenProject(UUID id) { projects.reopen(id); return project(id); }

    public OfficeDto.Goal moveGoal(UUID goalId, String projectId) {
        projects.moveGoal(goalId, uuid(projectId));
        return view.goal(goalId);
    }

    public List<BizDto.Snapshot> snapshots(UUID id) {
        LocalDate today = LocalDate.now(usageProps.reportZone());
        return projects.history(id, today.minusDays(365), today).stream()
                .map(h -> new BizDto.Snapshot(h.day().toString(), h.progressPct())).toList();
    }

    public List<BizDto.Activity> projectActivity(UUID id) {
        projects.get(id);
        return jdbc.query("""
                SELECT id, created_at, type, payload::text FROM domain_event
                WHERE aggregate_id = ? OR payload->>'projectId' = ? ORDER BY id DESC LIMIT 100""",
                (rs, i) -> new BizDto.Activity(String.valueOf(rs.getLong(1)), rs.getTimestamp(2).toInstant(), describe(rs.getString(3)), kind(rs.getString(3))),
                id, id.toString());
    }

    private static String describe(String type) {
        return switch (type) {
            case "project.created" -> "Dự án được tạo";
            case "project.updated" -> "Dự án được cập nhật";
            case "project.progress_changed" -> "Tiến độ thay đổi";
            case "task.created" -> "Có task mới";
            case "task.succeeded" -> "Một task hoàn thành";
            case "task.failed" -> "Một task thất bại";
            default -> type;
        };
    }

    private static String kind(String type) {
        if (type.endsWith("failed")) return "error";
        if (type.endsWith("succeeded")) return "success";
        return "info";
    }

    private record Maps(Map<UUID, Map<String, Long>> counts, Map<UUID, Long> cost, Map<UUID, Long> budget, Map<UUID, List<String>> goals) {}

    private Maps projectMaps() {
        Map<UUID, Map<String, Long>> counts = new HashMap<>();
        jdbc.query("SELECT project_id, status, count(*) FROM task WHERE project_id IS NOT NULL GROUP BY 1,2",
                rs -> { counts.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashMap<>()).put(rs.getString(2), rs.getLong(3)); });
        Map<UUID, Long> cost = new HashMap<>();
        jdbc.query("SELECT project_id, sum(cost_micro_usd) FROM usage_daily WHERE project_id <> ? GROUP BY 1",
                rs -> { cost.put(rs.getObject(1, UUID.class), rs.getLong(2)); }, new UUID(0, 0));
        Map<UUID, Long> budget = new HashMap<>();
        jdbc.query("SELECT scope_id, limit_micro_usd FROM budget WHERE scope_type = 'PROJECT' AND enabled ORDER BY CASE period WHEN 'TOTAL' THEN 1 ELSE 0 END",
                rs -> { budget.put(rs.getObject(1, UUID.class), rs.getLong(2)); });      // TOTAL ghi sau cùng nên thắng
        Map<UUID, List<String>> goals = new HashMap<>();
        jdbc.query("SELECT project_id, id FROM goal WHERE project_id IS NOT NULL ORDER BY created_at",
                rs -> { goals.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(rs.getString(2)); });
        return new Maps(counts, cost, budget, goals);
    }

    private static String feStatus(Project p) { return p.getStatus() == ProjectStatus.ARCHIVED ? "ARCHIVED" : "ACTIVE"; }

    private BizDto.Project project(Project p, Maps m) {
        Map<String, Long> c = m.counts().getOrDefault(p.getId(), Map.of());
        BizDto.Counts counts = new BizDto.Counts(n(c, "PENDING") + n(c, "BLOCKED") + n(c, "QUEUED"), n(c, "RUNNING"), n(c, "WAITING_APPROVAL"),
                n(c, "WAITING_BUDGET"), n(c, "SUCCEEDED"), n(c, "FAILED"), n(c, "CANCELLED"));
        return new BizDto.Project(p.getId().toString(), p.getName(), p.getDescription() == null ? "" : p.getDescription(), str(p.getDepartmentId()),
                p.getColor(), p.getDeadline(), feStatus(p), m.budget().get(p.getId()), m.cost().getOrDefault(p.getId(), 0L), p.getProgressPct(), counts,
                m.goals().getOrDefault(p.getId(), List.of()), p.getCreatedAt());
    }

    private static long n(Map<String, Long> m, String k) { return m.getOrDefault(k, 0L); }

    // ================================================================== chi phí
    public BizDto.Usage usage(String from, String to, String groupBy, UUID projectId, UUID agentId) {
        String group = groupBy == null || groupBy.isBlank() ? "agent" : groupBy;
        LocalDate f = day(from, LocalDate.now(usageProps.reportZone()).minusDays(29));
        LocalDate t = day(to, LocalDate.now(usageProps.reportZone()));
        long len = ChronoUnit.DAYS.between(f, t) + 1;
        List<UsageDto.SummaryRow> rows = usage.summary(f, t, group, agentId, projectId);
        long prev = len > 0 && len < 366 ? usage.summary(f.minusDays(len), f.minusDays(1), "model", agentId, projectId).stream().mapToLong(UsageDto.SummaryRow::costMicroUsd).sum() : 0;
        Map<String, String> labels = labels(group);
        List<BizDto.UsageRow> out = rows.stream().map(r -> {
            String key = r.key() == null ? "none" : r.key();
            return new BizDto.UsageRow(key, labels.getOrDefault(key, NONE.equals(key) || "none".equals(key) ? "Không xác định" : key),
                    r.costMicroUsd(), r.calls(), r.promptTokens(), r.completionTokens());
        }).toList();
        long total = rows.stream().mapToLong(UsageDto.SummaryRow::costMicroUsd).sum();
        long done = jdbc.queryForObject("SELECT count(*) FROM task WHERE status = 'SUCCEEDED' AND updated_at >= ? AND updated_at < ?" + (projectId != null ? " AND project_id = ?" : "")
                        + (agentId != null ? " AND assignee_agent_id = ?" : ""), Long.class, boundArgs(f, t, projectId, agentId));
        return new BizDto.Usage(f.toString(), t.toString(), group, total, prev, rows.stream().mapToLong(UsageDto.SummaryRow::calls).sum(),
                rows.stream().mapToLong(UsageDto.SummaryRow::promptTokens).sum(), rows.stream().mapToLong(UsageDto.SummaryRow::completionTokens).sum(),
                done == 0 ? null : total / done, rows.stream().mapToLong(UsageDto.SummaryRow::unpricedCalls).sum(), byDay(f, t, group, projectId, agentId), out);
    }

    private Object[] boundArgs(LocalDate f, LocalDate t, UUID projectId, UUID agentId) {
        List<Object> a = new ArrayList<>(List.of(java.sql.Timestamp.from(f.atStartOfDay(usageProps.reportZone()).toInstant()),
                java.sql.Timestamp.from(t.plusDays(1).atStartOfDay(usageProps.reportZone()).toInstant())));
        if (projectId != null) a.add(projectId);
        if (agentId != null) a.add(agentId);
        return a.toArray();
    }

    private List<BizDto.UsageDay> byDay(LocalDate f, LocalDate t, String group, UUID projectId, UUID agentId) {
        String key = switch (group) {                                   // danh sách trắng, khớp UsageRepository.summary
            case "agent" -> "agent_id::text";
            case "project" -> "project_id::text";
            case "department" -> "coalesce(department_id::text, 'none')";
            default -> "model";
        };
        StringBuilder sql = new StringBuilder("SELECT day, " + key + ", sum(cost_micro_usd) FROM usage_daily WHERE day BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>(List.of(java.sql.Date.valueOf(f), java.sql.Date.valueOf(t)));
        if (projectId != null) { sql.append(" AND project_id = ?"); args.add(projectId); }
        if (agentId != null) { sql.append(" AND agent_id = ?"); args.add(agentId); }
        sql.append(" GROUP BY 1, 2 ORDER BY 1");
        Map<String, Map<String, Long>> days = new LinkedHashMap<>();
        jdbc.query(sql.toString(), rs -> { days.computeIfAbsent(rs.getDate(1).toLocalDate().toString(), k -> new LinkedHashMap<>()).put(rs.getString(2), rs.getLong(3)); }, args.toArray());
        return days.entrySet().stream().map(e -> new BizDto.UsageDay(e.getKey(), e.getValue().values().stream().mapToLong(Long::longValue).sum(), e.getValue())).toList();
    }

    private Map<String, String> labels(String group) {
        String table = switch (group) { case "agent" -> "agent"; case "project" -> "project"; case "department" -> "department"; default -> null; };
        Map<String, String> m = new HashMap<>();
        if (table != null) jdbc.query("SELECT id::text, name FROM " + table, rs -> { m.put(rs.getString(1), rs.getString(2)); });   // table là hằng số
        return m;
    }

    public List<BizDto.ModelPrice> modelPrices() {
        return prices.list(null, null).stream().map(p -> new BizDto.ModelPrice(p.getId().toString(), p.getModel(), p.getInputMicroPerMtok(),
                p.getOutputMicroPerMtok(), p.getEffectiveFrom())).toList();
    }

    public BizDto.ModelPrice addModelPrice(BizDto.ModelPriceInput in) {
        if (in.model() == null || in.model().isBlank() || in.inputPerMTokMicro() == null || in.outputPerMTokMicro() == null)
            throw new AppException(ErrorCode.VALIDATION, "Cần model, giá input và giá output");
        String provider = prices.list(null, in.model()).stream().map(p -> p.getProvider()).findFirst().orElse("custom");
        Instant from = instant(in.effectiveFrom());
        var saved = prices.add(new ModelPriceDto.Put(provider, in.model().trim(), in.inputPerMTokMicro(), in.outputPerMTokMicro(), null,
                from == null ? Instant.now() : from, false, null), OWNER);
        return new BizDto.ModelPrice(saved.getId().toString(), saved.getModel(), saved.getInputMicroPerMtok(), saved.getOutputMicroPerMtok(), saved.getEffectiveFrom());
    }

    public List<BizDto.Budget> budgets() {
        return budgets.list(null, null).stream().filter(b -> b.getScopeType() != BudgetScope.GOAL).map(this::budget).toList();
    }

    public BizDto.Budget budget(UUID id) { return budget(budgets.get(id)); }

    private BizDto.Budget budget(Budget b) {
        BudgetDto.Status s = budgets.status(b.getId());
        long used = s.spentMicroUsd() + s.reservedMicroUsd();
        String state = used >= b.getLimitMicroUsd() ? "HARD" : used * 100 >= b.getLimitMicroUsd() * b.getSoftPct() ? "SOFT" : "OK";
        Long waiting = jdbc.queryForObject("SELECT count(*) FROM task WHERE status = 'WAITING_BUDGET' AND waiting_budget_id = ?", Long.class, b.getId());
        return new BizDto.Budget(b.getId().toString(), scopeOut(b.getScopeType()), str(b.getScopeId()), scopeLabel(b), b.getPeriod().name(),
                b.getLimitMicroUsd(), s.spentMicroUsd(), s.reservedMicroUsd(), waiting == null ? 0 : waiting, b.getSoftPct(),
                b.getHardAction() == HardAction.BLOCK ? "BLOCK" : "WARN", state);
    }

    private static String scopeOut(BudgetScope s) { return s == BudgetScope.GLOBAL ? "COMPANY" : s.name(); }

    private String scopeLabel(Budget b) {
        if (b.getScopeType() == BudgetScope.GLOBAL) return "Toàn công ty";
        String table = switch (b.getScopeType()) { case AGENT -> "agent"; case PROJECT -> "project"; case DEPARTMENT -> "department"; default -> null; };
        if (table == null || b.getScopeId() == null) return String.valueOf(b.getScopeType());
        List<String> n = jdbc.queryForList("SELECT name FROM " + table + " WHERE id = ?", String.class, b.getScopeId());   // table là hằng số
        return n.isEmpty() ? b.getScopeId().toString() : n.get(0);
    }

    public BizDto.Budget createBudget(BizDto.BudgetInput in) {
        if (in.scopeType() == null || in.period() == null || in.limitMicro() == null) throw new AppException(ErrorCode.VALIDATION, "Cần scopeType, period và limitMicro");
        BudgetScope scope = "COMPANY".equalsIgnoreCase(in.scopeType()) ? BudgetScope.GLOBAL : enumOf(BudgetScope.class, in.scopeType());
        Budget b = budgets.create(new BudgetDto.Create(scope, scope == BudgetScope.GLOBAL ? null : uuid(in.scopeId()), enumOf(BudgetPeriodType.class, in.period()),
                in.limitMicro(), null, in.warnPct(), hard(in.mode())), OWNER);
        return budget(b);
    }

    /** Kiểm tra yêu cầu sửa; trả hạn mức mới nếu là TĂNG (controller gọi BudgetService.raise: luật kiến trúc chỉ cho controller tăng hạn mức). */
    public Long validateBudgetUpdate(UUID id, BizDto.BudgetUpdate u) {
        Budget cur = budgets.get(id);
        if (u.limitMicro() == null || u.limitMicro() == cur.getLimitMicroUsd()) return null;
        if (u.limitMicro() < cur.getLimitMicroUsd()) throw new AppException(ErrorCode.VALIDATION, "Chỉ được tăng hạn mức, không giảm");
        if (u.reason() == null || u.reason().isBlank()) throw new AppException(ErrorCode.VALIDATION, "Tăng hạn mức cần ghi lý do");
        return u.limitMicro();
    }

    public BizDto.Budget finishBudgetUpdate(UUID id, BizDto.BudgetUpdate u) {
        if (u.warnPct() != null || u.mode() != null)
            budgets.patch(id, new BudgetDto.Patch(u.warnPct(), u.mode() == null ? null : hard(u.mode()), null, null), OWNER);
        return budget(id);
    }

    public List<BizDto.BudgetChange> budgetChanges(UUID id) {
        return budgets.changes(id).stream().map(c -> new BizDto.BudgetChange(c.getId().toString(), id.toString(), c.getChangedAt(),
                c.getOldLimit(), c.getNewLimit(), c.getReason())).toList();
    }

    // ================================================================== số liệu phòng
    public BizDto.DeptStats departmentStats(UUID id) {
        Long done = jdbc.queryForObject("""
                SELECT count(*) FROM task t JOIN agent a ON a.id = t.assignee_agent_id
                WHERE a.department_id = ? AND t.status = 'SUCCEEDED' AND t.updated_at >= ?""", Long.class, id,
                java.sql.Timestamp.from(LocalDate.now(usageProps.reportZone()).atStartOfDay(usageProps.reportZone()).toInstant()));
        Long cost = jdbc.queryForObject("SELECT coalesce(sum(cost_micro_usd), 0) FROM usage_daily WHERE day = ? AND department_id = ?", Long.class,
                java.sql.Date.valueOf(LocalDate.now(usageProps.reportZone())), id);
        return new BizDto.DeptStats(done == null ? 0 : done, cost == null ? 0.0 : cost / 1_000_000.0);
    }

    // ================================================================== helpers
    private static HardAction hard(String mode) { return "WARN".equalsIgnoreCase(mode) || "WARN_ONLY".equalsIgnoreCase(mode) ? HardAction.WARN_ONLY : HardAction.BLOCK; }

    private static <E extends Enum<E>> E enumOf(Class<E> c, String v) {
        try { return Enum.valueOf(c, v.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new AppException(ErrorCode.VALIDATION, "Giá trị không hợp lệ: " + v); }
    }

    private static UUID uuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { throw new AppException(ErrorCode.VALIDATION, "Id không hợp lệ: " + s); }
    }

    private static String str(UUID id) { return id == null ? null : id.toString(); }

    private static LocalDate day(String s, LocalDate dflt) {
        if (s == null || s.isBlank()) return dflt;
        try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s); }
        catch (RuntimeException e) { throw new AppException(ErrorCode.VALIDATION, "Ngày không hợp lệ: " + s); }
    }

    private static Instant instant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (RuntimeException ignored) { /* thử dạng khác */ }
        try { return OffsetDateTime.parse(s).toInstant(); } catch (RuntimeException ignored) { /* thử dạng khác */ }
        try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s).atStartOfDay(ZoneOffset.UTC).toInstant(); }
        catch (RuntimeException e) { throw new AppException(ErrorCode.VALIDATION, "Thời gian không hợp lệ: " + s); }
    }
}
