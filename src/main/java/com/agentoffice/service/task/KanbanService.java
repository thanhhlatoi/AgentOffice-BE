package com.agentoffice.service.task;

import com.agentoffice.domain.event.EventTypes;
import com.agentoffice.domain.task.KanbanColumn;
import com.agentoffice.domain.task.KanbanMoveRules;
import com.agentoffice.domain.task.LexoRank;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.domain.usage.ProgressCalculator;
import com.agentoffice.dto.task.KanbanDto;
import com.agentoffice.mapper.task.TaskMapper;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.entity.task.Task;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.repository.agent.AgentRepository;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.service.event.EventPublisher;
import com.agentoffice.util.TextNormalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bảng Kanban: đọc 5 cột và thực thi LỆNH kéo thả (không phải sửa trạng thái) theo KanbanMoveRules. */
@Service
public class KanbanService {
    public static final String STALE_VERSION = "STALE_VERSION";
    private static final Set<TaskStatus> NOT_STARTED = Set.of(TaskStatus.PENDING, TaskStatus.BLOCKED, TaskStatus.QUEUED);
    private static final String PRIO = "CASE t.priority WHEN 'HIGH' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END";

    /** from/to: ngày TẠO task (gồm cả hai đầu, theo múi giờ báo cáo); null = không giới hạn. */
    public record Filter(UUID projectId, UUID goalId, UUID agentId, UUID departmentId, Priority priority, String q, LocalDate from, LocalDate to) {
        public Filter(UUID projectId, UUID goalId, UUID agentId, UUID departmentId, Priority priority, String q) {
            this(projectId, goalId, agentId, departmentId, priority, q, null, null);
        }
    }
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");   // khớp mặc định agentoffice.usage.report-zone

    private final JdbcTemplate jdbc;
    private final TaskRepository tasks;
    private final TaskService taskService;
    private final AgentRepository agents;
    private final LexoRank lexo;
    private final ProgressCalculator calculator;
    private final EventPublisher events;
    private final Clock clock;
    private final TaskMapper taskMapper;

    public KanbanService(JdbcTemplate jdbc, TaskRepository tasks, TaskService taskService, AgentRepository agents, LexoRank lexo,
                         ProgressCalculator calculator, EventPublisher events, Clock clock, TaskMapper taskMapper) {
        this.taskMapper = taskMapper;
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.taskService = taskService;
        this.agents = agents;
        this.lexo = lexo;
        this.calculator = calculator;
        this.events = events;
        this.clock = clock;
    }

    // ================================================================== đọc
    /** onlyColumn != null + cursor: tải thêm cho MỘT cột. */
    @Transactional(readOnly = true)
    public KanbanDto.Board board(Filter f, int limit, KanbanColumn onlyColumn, String cursor) {
        int n = Math.max(1, Math.min(limit, 200));
        List<KanbanDto.Column> cols = new ArrayList<>();
        for (KanbanColumn c : KanbanColumn.values()) {
            if (onlyColumn != null && onlyColumn != c) continue;
            cols.add(column(c, f, n, onlyColumn == c ? cursor : null));
        }
        return new KanbanDto.Board(cols);
    }

    private KanbanDto.Column column(KanbanColumn c, Filter f, int limit, String cursor) {
        List<Object> args = new ArrayList<>();
        String where = where(c, f, args);
        long total = jdbc.queryForObject("SELECT count(*) FROM task t LEFT JOIN agent a ON a.id = t.assignee_agent_id " + where, Long.class, args.toArray());

        boolean recent = c == KanbanColumn.DONE || c == KanbanColumn.CLOSED;     // done/closed: gần nhất trước; còn lại: đúng thứ tự chạy
        List<Object> pageArgs = new ArrayList<>(args);
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.title, t.assignee_agent_id, a.name, t.priority, t.status, t.project_id, p.color, t.goal_id, g.title,
                       t.version, t.retry_of_task_id, t.created_at, t.updated_at, t.kanban_rank,
                       (SELECT coalesce(sum(u.cost_micro_usd), 0) FROM usage_record u WHERE u.task_id = t.id),
                       (SELECT count(*) FROM task ch WHERE ch.parent_task_id = t.id)
                FROM task t LEFT JOIN agent a ON a.id = t.assignee_agent_id LEFT JOIN project p ON p.id = t.project_id
                LEFT JOIN goal g ON g.id = t.goal_id """).append(where);
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] k = cursor.split("\\|", 3);
                if (recent) {
                    sql.append(" AND (t.updated_at, t.id) < (?, ?)");
                    pageArgs.add(OffsetDateTime.ofInstant(Instant.ofEpochSecond(0, Long.parseLong(k[0]) * 1000), ZoneOffset.UTC));
                    pageArgs.add(UUID.fromString(k[1]));
                } else {
                    sql.append(" AND (").append(PRIO).append(", t.kanban_rank, t.id) > (?, ?, ?)");
                    pageArgs.add(Integer.parseInt(k[0]));
                    pageArgs.add(k[1]);
                    pageArgs.add(UUID.fromString(k[2]));
                }
            } catch (RuntimeException e) {
                throw new AppException(ErrorCode.VALIDATION, "cursor không hợp lệ");
            }
        }
        sql.append(recent ? " ORDER BY t.updated_at DESC, t.id DESC" : " ORDER BY " + PRIO + ", t.kanban_rank, t.id").append(" LIMIT ?");
        pageArgs.add(limit + 1);

        record Row(KanbanDto.Card card, long childCount, Instant updatedAt, String rank) {}
        Instant now = clock.instant();
        List<Row> rows = jdbc.query(sql.toString(), (rs, i) -> {
            TaskStatus st = TaskStatus.valueOf(rs.getString(6));
            Instant created = rs.getTimestamp(13).toInstant();
            KanbanDto.Card card = new KanbanDto.Card(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4),
                    Priority.valueOf(rs.getString(5)), st, rs.getObject(7, UUID.class), rs.getString(8), rs.getObject(9, UUID.class),
                    rs.getString(10), null, rs.getLong(16), Math.max(0, Duration.between(created, now).getSeconds()), lockedReason(st),
                    rs.getLong(11), rs.getObject(12, UUID.class), created, rs.getTimestamp(14).toInstant());
            return new Row(card, rs.getLong(17), rs.getTimestamp(14).toInstant(), rs.getString(15));
        }, pageArgs.toArray());

        boolean more = rows.size() > limit;
        List<Row> page = more ? rows.subList(0, limit) : rows;
        List<KanbanDto.Card> cards = new ArrayList<>();
        for (Row r : page) cards.add(r.childCount() > 0 ? withProgress(r.card()) : r.card());
        String next = null;
        if (more) {
            Row last = page.get(page.size() - 1);
            next = recent ? (last.updatedAt().getEpochSecond() * 1_000_000L + last.updatedAt().getNano() / 1000) + "|" + last.card().id()
                    : prioOf(last.card().priority()) + "|" + last.rank() + "|" + last.card().id();
        }
        return new KanbanDto.Column(c.name().toLowerCase(Locale.ROOT), total, cards, next);
    }

    private KanbanDto.Card withProgress(KanbanDto.Card c) {
        List<ProgressCalculator.Item> kids = jdbc.query("SELECT weight, status FROM task WHERE parent_task_id = ?",
                (rs, i) -> new ProgressCalculator.Item(rs.getInt(1), rs.getString(2)), c.id());
        return new KanbanDto.Card(c.id(), c.title(), c.assigneeAgentId(), c.assigneeName(), c.priority(), c.status(), c.projectId(),
                c.projectColor(), c.goalId(), c.goalTitle(), calculator.percent(kids), c.costMicroUsd(), c.ageSec(), c.lockedReason(),
                c.version(), c.retryOfTaskId(), c.createdAt(), c.updatedAt());
    }

    private static int prioOf(Priority p) { return switch (p) { case HIGH -> 0; case NORMAL -> 1; case LOW -> 2; }; }

    /** Thẻ không kéo đi đâu được: đang chờ duyệt/ngân sách (dùng hộp thoại) hoặc đã huỷ (không có cạnh đi ra). */
    private static String lockedReason(TaskStatus s) {
        return switch (s) {
            case WAITING_APPROVAL, WAITING_BUDGET -> KanbanMoveRules.USE_APPROVAL_DIALOG;
            case CANCELLED -> KanbanMoveRules.NOT_ALLOWED;
            default -> null;
        };
    }

    private String where(KanbanColumn c, Filter f, List<Object> args) {
        StringBuilder w = new StringBuilder(" WHERE t.status IN (");
        List<TaskStatus> st = c.statuses();
        for (int i = 0; i < st.size(); i++) { w.append(i == 0 ? "?" : ",?"); args.add(st.get(i).name()); }
        w.append(")");
        if (f.projectId() != null) { w.append(" AND t.project_id = ?"); args.add(f.projectId()); }
        if (f.goalId() != null) { w.append(" AND t.goal_id = ?"); args.add(f.goalId()); }
        if (f.agentId() != null) { w.append(" AND t.assignee_agent_id = ?"); args.add(f.agentId()); }
        if (f.departmentId() != null) { w.append(" AND a.department_id = ?"); args.add(f.departmentId()); }
        if (f.priority() != null) { w.append(" AND t.priority = ?"); args.add(f.priority().name()); }
        if (f.q() != null && !f.q().isBlank()) {
            String needle = TextNormalizer.forSearch(f.q()).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            w.append(" AND unaccent(lower(t.title)) LIKE ? ESCAPE '\\'");
            args.add("%" + needle + "%");
        }
        if (f.from() != null) { w.append(" AND t.created_at >= ?"); args.add(OffsetDateTime.ofInstant(f.from().atStartOfDay(REPORT_ZONE).toInstant(), ZoneOffset.UTC)); }
        if (f.to() != null) { w.append(" AND t.created_at < ?"); args.add(OffsetDateTime.ofInstant(f.to().plusDays(1).atStartOfDay(REPORT_ZONE).toInstant(), ZoneOffset.UTC)); }
        return w.toString();
    }

    // ================================================================== lệnh kéo thả
    @Transactional
    public KanbanDto.MoveResult move(UUID id, KanbanColumn to, UUID beforeId, UUID afterId, long expectedVersion) {
        Task t = tasks.findById(id).orElseThrow(() -> new NotFoundException("task", id));
        if (t.getVersion() != expectedVersion) throw conflict(STALE_VERSION, "Thẻ vừa bị thay đổi, hãy tải lại");
        KanbanColumn from = KanbanColumn.of(t.getStatus());
        KanbanMoveRules.Decision d = KanbanMoveRules.decide(t.getStatus(), to);
        if (d.rejected()) throw conflict(d.reasonCode(), "Không thể kéo thẻ " + t.getStatus() + " sang cột " + to.name().toLowerCase(Locale.ROOT));

        Task result;
        try {
            result = switch (d.action()) {
                case REORDER -> { reorder(t, beforeId, afterId); yield tasks.findById(id).orElseThrow(); }
                case PROMOTE -> {
                    t.setPriority(Priority.HIGH);
                    t.setNextRunAt(clock.instant());               // bỏ qua giờ chờ backoff; scheduler vẫn quyết lúc nào chạy thật
                    yield tasks.saveAndFlush(t);
                }
                case RERUN -> {
                    Task fresh = rerun(id);
                    if (beforeId != null || afterId != null) reorder(fresh, beforeId, afterId);
                    yield tasks.findById(fresh.getId()).orElseThrow();
                }
                case CANCEL -> taskService.cancel(id);
                case REJECT -> throw new IllegalStateException();
            };
        } catch (OptimisticLockingFailureException e) {
            throw conflict(STALE_VERSION, "Thẻ vừa bị người khác thay đổi, hãy tải lại");
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("taskId", id);
        p.put("from", from.name().toLowerCase(Locale.ROOT));
        p.put("to", to.name().toLowerCase(Locale.ROOT));
        p.put("action", d.action().name());
        if (!result.getId().equals(id)) p.put("newTaskId", result.getId());
        events.publish(EventTypes.TASK_MOVED, id, p);
        return new KanbanDto.MoveResult(d.action().name(), taskMapper.toDto(result));
    }

    private Task rerun(UUID id) {
        try {
            return taskService.rerun(id);
        } catch (IllegalStateException e) {
            throw conflict("GOAL_NOT_RUNNING", e.getMessage());
        }
    }

    /** Đặt `t` vào giữa afterId (ngay trước nó) và beforeId (ngay sau nó). Câu UPDATE kiểm version: đua nhau thì một bên 409. */
    private void reorder(Task t, UUID beforeId, UUID afterId) {
        if (beforeId == null && afterId == null) throw new AppException(ErrorCode.VALIDATION, "Cần beforeTaskId hoặc afterTaskId để biết chèn vào đâu");
        if (t.getId().equals(beforeId) || t.getId().equals(afterId)) throw new AppException(ErrorCode.VALIDATION, "Không thể chèn thẻ cạnh chính nó");
        String upper = beforeId == null ? null : rankOfNeighbour(beforeId);
        String lower = afterId == null ? null : rankOfNeighbour(afterId);
        if (lower != null && upper != null && lower.compareTo(upper) >= 0) throw conflict("INVALID_NEIGHBORS", "Hai thẻ kề không đúng thứ tự, hãy tải lại bảng");
        String rank;
        try {
            rank = lexo.between(lower, upper);
        } catch (IllegalStateException e) {
            throw conflict("RANK_EXHAUSTED", "Hết chỗ chèn giữa hai thẻ này (hệ thống sẽ dồn lại thứ tự, hãy thử lại)");
        }
        if (tasks.updateRank(t.getId(), rank, t.getVersion()) == 0) throw conflict(STALE_VERSION, "Thẻ vừa bị người khác thay đổi, hãy tải lại");
    }

    private String rankOfNeighbour(UUID id) {
        String r = tasks.rankOf(id);
        if (r == null) throw new NotFoundException("task", id);
        return r;
    }

    // ================================================================== giao việc
    @Transactional
    public Task assign(UUID id, UUID agentId, long expectedVersion) {
        Task t = tasks.findById(id).orElseThrow(() -> new NotFoundException("task", id));
        if (t.getVersion() != expectedVersion) throw conflict(STALE_VERSION, "Thẻ vừa bị thay đổi, hãy tải lại");
        if (!NOT_STARTED.contains(t.getStatus())) throw conflict("ALREADY_STARTED", "Task đã chạy/kết thúc (" + t.getStatus() + "), không giao lại được");
        Agent a = agents.findById(agentId).orElseThrow(() -> new NotFoundException("agent", agentId));
        if (!a.isActive()) throw new AppException(ErrorCode.AGENT_UNAVAILABLE, "Agent " + a.getName() + " đang tắt");
        // TODO(BE-10): kiểm agent có đủ tool/quyền mà task cần (chưa có hệ tool-grant cho task).
        if (agentId.equals(t.getAssigneeAgentId())) return t;
        UUID from = t.getAssigneeAgentId();
        try {
            t.reassign(agentId);
            tasks.saveAndFlush(t);
        } catch (OptimisticLockingFailureException e) {
            throw conflict(STALE_VERSION, "Thẻ vừa bị người khác thay đổi, hãy tải lại");
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("taskId", id);
        p.put("action", "ASSIGN");
        p.put("fromAgentId", from);
        p.put("toAgentId", agentId);
        events.publish(EventTypes.TASK_MOVED, id, p);
        return t;
    }

    private static AppException conflict(String reasonCode, String message) {
        return new AppException(ErrorCode.CONFLICT, message, Map.of("reasonCode", reasonCode));
    }
}
