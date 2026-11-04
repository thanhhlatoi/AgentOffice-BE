package com.agentoffice.service.project;

import com.agentoffice.config.UsageProperties;
import com.agentoffice.domain.event.EventTypes;
import com.agentoffice.domain.project.ProjectStatus;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.domain.usage.ProgressCalculator;
import com.agentoffice.dto.project.ProjectDto;
import com.agentoffice.entity.project.Project;
import com.agentoffice.mapper.project.ProjectMapper;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.repository.project.ProjectRepository;
import com.agentoffice.repository.task.GoalRepository;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.service.event.EventPublisher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
    private static final java.util.Set<String> GOAL_DONE = java.util.Set.of("SUCCEEDED", "CANCELLED");

    private final ProjectRepository projects;
    private final GoalRepository goals;
    private final TaskRepository tasks;
    private final ProgressCalculator calculator;
    private final EventPublisher events;
    private final JdbcTemplate jdbc;
    private final UsageProperties usageProps;
    private final Clock clock;
    private final ProjectMapper mapper;

    public ProjectService(ProjectRepository projects, GoalRepository goals, TaskRepository tasks, ProgressCalculator calculator,
                          EventPublisher events, JdbcTemplate jdbc, UsageProperties usageProps, Clock clock, ProjectMapper mapper) {
        this.mapper = mapper;
        this.projects = projects;
        this.goals = goals;
        this.tasks = tasks;
        this.calculator = calculator;
        this.events = events;
        this.jdbc = jdbc;
        this.usageProps = usageProps;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ CRUD
    @Transactional
    public Project create(ProjectDto.Create r) {
        requireExists("department", r.departmentId());
        requireExists("agent", r.ownerAgentId());
        Project p = projects.saveAndFlush(new Project(r.name(), r.description(), r.departmentId(), r.ownerAgentId(), r.color(), r.deadline()));
        events.publish(EventTypes.PROJECT_CREATED, p.getId(), Map.of("projectId", p.getId(), "name", p.getName()));
        return p;
    }

    @Transactional(readOnly = true)
    public List<Project> list(List<ProjectStatus> statuses) {
        return statuses == null || statuses.isEmpty() ? projects.findAllByOrderByCreatedAtDesc() : projects.findByStatusInOrderByCreatedAtDesc(statuses);
    }

    @Transactional(readOnly = true)
    public Project get(UUID id) { return projects.findById(id).orElseThrow(() -> new NotFoundException("project", id)); }

    @Transactional
    public Project patch(UUID id, ProjectDto.Patch r) {
        Project p = projects.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("project", id));
        if (p.getStatus() == ProjectStatus.ARCHIVED) throw new AppException(ErrorCode.CONFLICT, "Dự án đã lưu trữ, không sửa được");
        if (r.status() != null) {
            if (r.status() != ProjectStatus.ACTIVE && r.status() != ProjectStatus.PAUSED)
                throw new AppException(ErrorCode.UNPROCESSABLE, "Chỉ đặt tay được ACTIVE|PAUSED (DONE do hệ thống, ARCHIVED qua /archive)");
            if (p.getStatus() == ProjectStatus.DONE && r.status() == ProjectStatus.ACTIVE) { /* mở lại tay: recompute sẽ đóng lại nếu thật sự xong */ }
            p.setStatus(r.status());
            if (r.status() == ProjectStatus.ACTIVE) p.setFinishedAt(null);
        }
        if (r.name() != null) p.setName(r.name());
        if (r.description() != null) p.setDescription(r.description());
        if (r.departmentId() != null) { requireExists("department", r.departmentId()); p.setDepartmentId(r.departmentId()); }
        if (r.ownerAgentId() != null) { requireExists("agent", r.ownerAgentId()); p.setOwnerAgentId(r.ownerAgentId()); }
        if (r.color() != null) p.setColor(r.color());
        if (r.deadline() != null) p.setDeadline(r.deadline());
        projects.saveAndFlush(p);
        events.publish(EventTypes.PROJECT_UPDATED, id, Map.of("projectId", id));
        return p;
    }

    /** Không lưu trữ khi còn task đang mở: tránh bỏ rơi việc đang chạy. */
    @Transactional
    public Project archive(UUID id) {
        Project p = projects.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("project", id));
        if (p.getStatus() == ProjectStatus.ARCHIVED) return p;
        Long open = jdbc.queryForObject("SELECT count(*) FROM task WHERE project_id = ? AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')", Long.class, id);
        if (open != null && open > 0) throw new AppException(ErrorCode.CONFLICT, "Còn " + open + " task đang mở, hãy huỷ hoặc chờ chúng xong");
        p.setStatus(ProjectStatus.ARCHIVED);
        projects.saveAndFlush(p);
        events.publish(EventTypes.PROJECT_UPDATED, id, Map.of("projectId", id, "status", "ARCHIVED"));
        return p;
    }

    // ------------------------------------------------------------------ chi tiết
    @Transactional(readOnly = true)
    public ProjectDto.Detail detail(UUID id) {
        Project p = get(id);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (TaskStatus s : TaskStatus.values()) byStatus.put(s.name(), 0L);
        jdbc.query("SELECT status, count(*) FROM task WHERE project_id = ? GROUP BY status", rs -> { byStatus.put(rs.getString(1), rs.getLong(2)); }, id);
        long cost = jdbc.queryForObject("SELECT coalesce(sum(cost_micro_usd), 0) FROM usage_daily WHERE project_id = ?", Long.class, id);
        List<Object[]> b = jdbc.query("""
                SELECT limit_micro_usd, period FROM budget WHERE scope_type = 'PROJECT' AND scope_id = ? AND enabled
                ORDER BY CASE period WHEN 'TOTAL' THEN 0 WHEN 'MONTH' THEN 1 ELSE 2 END LIMIT 1""",
                (rs, i) -> new Object[] {rs.getLong(1), rs.getString(2)}, id);
        Long remaining = p.getDeadline() == null ? null : Duration.between(clock.instant(), p.getDeadline()).getSeconds();
        boolean overdue = p.getDeadline() != null && clock.instant().isAfter(p.getDeadline()) && p.getStatus() != ProjectStatus.DONE;
        List<ProjectDto.GoalRow> goalRows = new ArrayList<>();
        for (Goal g : jdbc.query("SELECT id FROM goal WHERE project_id = ? ORDER BY created_at", (rs, i) -> rs.getObject(1, UUID.class), id)
                .stream().map(gid -> goals.findById(gid).orElseThrow()).toList()) {
            List<ProgressCalculator.Item> items = items("goal_id", g.getId());
            goalRows.add(new ProjectDto.GoalRow(g.getId(), g.getTitle(), g.getStatus().name(), calculator.percent(items), items.size()));
        }
        return new ProjectDto.Detail(mapper.toView(p), byStatus, byStatus.get("FAILED"),
                byStatus.get("WAITING_APPROVAL"), cost, b.isEmpty() ? null : (Long) b.get(0)[0], b.isEmpty() ? null : (String) b.get(0)[1],
                remaining, overdue, goalRows);
    }

    @Transactional(readOnly = true)
    public List<ProjectDto.HistoryPoint> history(UUID id, LocalDate from, LocalDate to) {
        get(id);
        if (to.isBefore(from)) throw new AppException(ErrorCode.VALIDATION, "to phải >= from");
        return jdbc.query("SELECT day, progress_pct, succeeded, failed, total FROM project_progress_daily WHERE project_id = ? AND day BETWEEN ? AND ? ORDER BY day",
                (rs, i) -> new ProjectDto.HistoryPoint(rs.getDate(1).toLocalDate(), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)),
                id, java.sql.Date.valueOf(from), java.sql.Date.valueOf(to));
    }

    // ------------------------------------------------------------------ tiến độ
    /**
     * TÍNH LẠI từ dữ liệu task (không cộng trừ dần => idempotent). Khoá hàng dự án trước khi đọc: hai task kết thúc cùng lúc ở hai
     * transaction xếp hàng, kẻ sau thấy kết quả đã commit của kẻ trước nên giá trị cuối luôn đúng. Phát project.progress_changed
     * CHỈ khi giá trị đổi. Cũng tự đóng DONE / mở lại ACTIVE (không đụng PAUSED, ARCHIVED).
     */
    @Transactional
    public void recompute(UUID projectId) {
        Project p = projects.findByIdForUpdate(projectId).orElse(null);
        if (p == null) return;
        List<ProgressCalculator.Item> items = items("project_id", projectId);
        int pct = calculator.percent(items);
        boolean hasPlan = items.stream().anyMatch(i -> !"CANCELLED".equals(i.status()));
        int old = p.getProgressPct();
        p.setProgressPct(pct);
        p.setHasPlan(hasPlan);

        boolean noOpenTasks = items.stream().noneMatch(i -> !TaskStatus.valueOf(i.status()).isTerminal());
        List<String> goalStatuses = jdbc.queryForList("SELECT status FROM goal WHERE project_id = ?", String.class, projectId);
        boolean done = noOpenTasks && !goalStatuses.isEmpty() && GOAL_DONE.containsAll(goalStatuses) && goalStatuses.contains("SUCCEEDED");
        ProjectStatus before = p.getStatus();
        if (before == ProjectStatus.ACTIVE && done) { p.setStatus(ProjectStatus.DONE); p.setFinishedAt(clock.instant()); }
        else if (before == ProjectStatus.DONE && !done) { p.setStatus(ProjectStatus.ACTIVE); p.setFinishedAt(null); }
        projects.saveAndFlush(p);

        if (pct != old) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("projectId", projectId);
            e.put("progressPct", pct);
            e.put("previousPct", old);
            events.publish(EventTypes.PROJECT_PROGRESS_CHANGED, projectId, e);
        }
        if (p.getStatus() != before)
            events.publish(EventTypes.PROJECT_UPDATED, projectId, Map.of("projectId", projectId, "status", p.getStatus().name()));
    }

    private List<ProgressCalculator.Item> items(String column, UUID id) {   // column là hằng số của lớp này
        return jdbc.query("SELECT weight, status FROM task WHERE " + column + " = ?",
                (rs, i) -> new ProgressCalculator.Item(rs.getInt(1), rs.getString(2)), id);
    }

    /** Chuyển goal (kèm mọi task của nó) sang dự án khác hoặc ra khỏi dự án (null); cả hai dự án tính lại tiến độ. */
    @Transactional
    public Goal moveGoal(UUID goalId, UUID newProjectId) {
        Goal g = goals.findByIdForUpdate(goalId).orElseThrow(() -> new NotFoundException("goal", goalId));
        if (newProjectId != null) {
            Project target = get(newProjectId);
            if (target.getStatus() == ProjectStatus.ARCHIVED) throw new AppException(ErrorCode.UNPROCESSABLE, "Dự án đích đã lưu trữ");
        }
        UUID old = g.getProjectId();
        if (java.util.Objects.equals(old, newProjectId)) return g;
        g.setProjectId(newProjectId);
        goals.saveAndFlush(g);
        tasks.moveGoalTasksToProject(goalId, newProjectId);
        List<UUID> affected = new ArrayList<>();
        if (old != null) affected.add(old);
        if (newProjectId != null) affected.add(newProjectId);
        affected.sort(null);                                       // khoá theo thứ tự id cố định: không deadlock giữa hai lần chuyển ngược chiều
        affected.forEach(this::recompute);
        return g;
    }

    /** Ảnh chụp tiến độ của hôm nay (theo múi giờ báo cáo) cho mọi dự án chưa lưu trữ; chạy lại cùng ngày thì ghi đè. */
    @Transactional
    public int snapshotToday() {
        LocalDate day = clock.instant().atZone(usageProps.reportZone()).toLocalDate();
        return jdbc.update("""
                INSERT INTO project_progress_daily (project_id, day, progress_pct, succeeded, failed, total)
                SELECT p.id, ?, p.progress_pct,
                       count(t.id) FILTER (WHERE t.status = 'SUCCEEDED'), count(t.id) FILTER (WHERE t.status = 'FAILED'),
                       count(t.id) FILTER (WHERE t.status <> 'CANCELLED')
                FROM project p LEFT JOIN task t ON t.project_id = p.id WHERE p.status <> 'ARCHIVED' GROUP BY p.id
                ON CONFLICT (project_id, day) DO UPDATE SET progress_pct = EXCLUDED.progress_pct, succeeded = EXCLUDED.succeeded,
                    failed = EXCLUDED.failed, total = EXCLUDED.total""", java.sql.Date.valueOf(day));
    }

    private void requireExists(String table, UUID id) {
        if (id == null) return;
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Long.class, id);   // table là hằng số
        if (n == null || n == 0) throw new NotFoundException(table, id);
    }
}
