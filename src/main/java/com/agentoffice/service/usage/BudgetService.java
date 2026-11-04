package com.agentoffice.service.usage;

import com.agentoffice.config.BudgetProperties;
import com.agentoffice.config.UsageProperties;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.domain.usage.BudgetPeriodType;
import com.agentoffice.domain.usage.BudgetPeriods;
import com.agentoffice.domain.usage.BudgetScope;
import com.agentoffice.domain.usage.HardAction;
import com.agentoffice.dto.usage.BudgetDto;
import com.agentoffice.entity.task.Task;
import com.agentoffice.entity.usage.Budget;
import com.agentoffice.entity.usage.BudgetChange;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.repository.usage.BudgetChangeRepository;
import com.agentoffice.repository.usage.BudgetRepository;
import com.agentoffice.service.audit.PermissionAuditLog;
import com.agentoffice.service.task.TaskService;
import java.math.BigInteger;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD ngân sách, trạng thái, tăng hạn mức và tự chạy tiếp task WAITING_BUDGET. Chỉ CON NGƯỜI (BudgetController) gọi
 * create/patch/delete/raise; ArchitectureTest cấm agent/tool/runner chạm vào lớp này.
 */
@Service
public class BudgetService {
    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);
    private static final long RESUME_LOCK_KEY = 0x4255444745L;   // "BUDGE"

    private final BudgetRepository budgets;
    private final BudgetChangeRepository changes;
    private final TaskRepository tasks;
    private final TaskService taskService;
    private final JdbcTemplate jdbc;
    private final PermissionAuditLog audit;
    private final BudgetProperties props;
    private final UsageProperties usageProps;
    private final Clock clock;

    public BudgetService(BudgetRepository budgets, BudgetChangeRepository changes, TaskRepository tasks, TaskService taskService,
                         JdbcTemplate jdbc, PermissionAuditLog audit, BudgetProperties props, UsageProperties usageProps, Clock clock) {
        this.budgets = budgets;
        this.changes = changes;
        this.tasks = tasks;
        this.taskService = taskService;
        this.jdbc = jdbc;
        this.audit = audit;
        this.props = props;
        this.usageProps = usageProps;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ CRUD
    @Transactional
    public Budget create(BudgetDto.Create r, String actor) {
        requireScope(r.scopeType(), r.scopeId());
        Budget b = new Budget(r.scopeType(), r.scopeId(), r.period(), r.limitMicroUsd(), r.limitTokens(),
                r.softPct() == null ? props.defaultSoftPct() : r.softPct(), r.hardAction() == null ? HardAction.BLOCK : r.hardAction());
        try {
            b = budgets.saveAndFlush(b);
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.CONFLICT, "Đã có ngân sách " + r.scopeType() + "/" + r.period() + " cho phạm vi này", null, e);
        }
        audit.record(actor, "BUDGET_CREATE", auditMap(b));
        return b;
    }

    @Transactional(readOnly = true)
    public List<Budget> list(BudgetScope scopeType, UUID scopeId) {
        return budgets.findAll().stream()
                .filter(b -> scopeType == null || b.getScopeType() == scopeType)
                .filter(b -> scopeId == null || scopeId.equals(b.getScopeId())).toList();
    }

    @Transactional(readOnly = true)
    public Budget get(UUID id) { return budgets.findById(id).orElseThrow(() -> new NotFoundException("budget", id)); }

    @Transactional
    public Budget patch(UUID id, BudgetDto.Patch p, String actor) {
        Budget b = budgets.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("budget", id));
        if (p.softPct() != null) b.setSoftPct(p.softPct());
        if (p.hardAction() != null) b.setHardAction(p.hardAction());
        if (p.enabled() != null) b.setEnabled(p.enabled());
        if (p.limitTokens() != null) b.setLimitTokens(p.limitTokens());
        budgets.saveAndFlush(b);
        audit.record(actor, "BUDGET_PATCH", auditMap(b));
        resumeEligible();   // tắt ngân sách hoặc chuyển WARN_ONLY cũng mở đường cho task đang chờ
        return b;
    }

    /** Có task đang chờ ngân sách này, hoặc đã có lịch sử đổi hạn mức (bảng chỉ-thêm) thì không xoá được. */
    @Transactional
    public void delete(UUID id, String actor) {
        Budget b = budgets.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("budget", id));
        Long waiting = jdbc.queryForObject("SELECT count(*) FROM task WHERE status = 'WAITING_BUDGET' AND waiting_budget_id = ?", Long.class, id);
        if (waiting != null && waiting > 0)
            throw new AppException(ErrorCode.CONFLICT, "Còn " + waiting + " task đang chờ ngân sách này, hãy tăng hạn mức hoặc huỷ chúng trước");
        if (changes.existsByBudgetId(id))
            throw new AppException(ErrorCode.CONFLICT, "Ngân sách đã có lịch sử đổi hạn mức nên không xoá được; hãy tắt (enabled=false)");
        jdbc.update("DELETE FROM budget_counter WHERE budget_id = ?", id);
        budgets.delete(b);
        audit.record(actor, "BUDGET_DELETE", auditMap(b));
    }

    // ------------------------------------------------------------------ trạng thái
    @Transactional(readOnly = true)
    public BudgetDto.Status status(UUID id) {
        Budget b = get(id);
        LocalDate start = BudgetPeriods.startOf(b.getPeriod(), clock.instant(), usageProps.reportZone());
        long[] c = {0, 0, 0};
        boolean[] f = {false, false};
        jdbc.query("SELECT spent_micro, reserved_micro, spent_tokens, soft_alerted, hard_alerted FROM budget_counter WHERE budget_id = ? AND period_start = ?",
                rs -> { c[0] = rs.getLong(1); c[1] = rs.getLong(2); c[2] = rs.getLong(3); f[0] = rs.getBoolean(4); f[1] = rs.getBoolean(5); }, id, start);
        long used = Math.addExact(c[0], c[1]);
        long pct = BigInteger.valueOf(used).multiply(BigInteger.valueOf(100)).divide(BigInteger.valueOf(b.getLimitMicroUsd())).longValue();
        return new BudgetDto.Status(id, b.getPeriod(), start, b.getLimitMicroUsd(), c[0], c[1],
                Math.max(0, b.getLimitMicroUsd() - used), pct, c[2], f[0], f[1]);
    }

    @Transactional(readOnly = true)
    public List<BudgetChange> changes(UUID id) {
        get(id);
        return changes.findByBudgetIdOrderByChangedAtDesc(id);
    }

    // ------------------------------------------------------------------ tăng hạn mức (chỉ con người)
    /** Chỉ TĂNG. Ghi BudgetChange + audit, rồi cho task đang chờ chạy tiếp ngay (không đợi job mỗi phút). */
    @Transactional
    public Budget raise(UUID id, long newLimit, String reason, String actor) {
        Budget b = budgets.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("budget", id));
        if (newLimit <= b.getLimitMicroUsd())
            throw new AppException(ErrorCode.UNPROCESSABLE, "Chỉ được tăng hạn mức (hiện " + b.getLimitMicroUsd() + ", yêu cầu " + newLimit + ")");
        long old = b.getLimitMicroUsd();
        b.setLimitMicroUsd(newLimit);
        budgets.saveAndFlush(b);
        changes.saveAndFlush(new BudgetChange(id, old, newLimit, reason, actor, clock.instant()));
        Map<String, Object> d = auditMap(b);
        d.put("oldLimit", old);
        d.put("reason", reason);
        audit.record(actor, "BUDGET_RAISE", d);
        resumeEligible();
        return b;
    }

    // ------------------------------------------------------------------ tự chạy tiếp
    /**
     * Đưa task WAITING_BUDGET về QUEUED nếu ngân sách đã chặn nó giờ đủ chỗ cho phần ước tính bị từ chối
     * (do tăng hạn mức, sang kỳ mới nên counter bắt đầu từ 0, tắt ngân sách, hoặc chuyển WARN_ONLY).
     * Khoá tư vấn: hai nơi (job mỗi phút + raise) không chạy chồng; bên đến sau bỏ qua, lượt job kế sẽ bắt kịp.
     * @return số task đã chạy tiếp
     */
    @Transactional
    public int resumeEligible() {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, RESUME_LOCK_KEY))) return 0;
        int n = 0;
        for (Task t : tasks.findByStatus(TaskStatus.WAITING_BUDGET)) {
            if (!canResume(t)) continue;
            taskService.resumeFromBudget(t.getId());
            n++;
        }
        if (n > 0) log.info("Chạy tiếp {} task đang chờ ngân sách", n);
        return n;
    }

    private boolean canResume(Task t) {
        if (t.getWaitingBudgetId() == null) return true;
        Budget b = budgets.findById(t.getWaitingBudgetId()).orElse(null);
        if (b == null || !b.isEnabled() || b.getHardAction() == HardAction.WARN_ONLY) return true;
        LocalDate start = BudgetPeriods.startOf(b.getPeriod(), clock.instant(), usageProps.reportZone());
        List<Long> used = jdbc.query("SELECT spent_micro + reserved_micro FROM budget_counter WHERE budget_id = ? AND period_start = ?",
                (rs, i) -> rs.getLong(1), b.getId(), start);
        long u = used.isEmpty() ? 0 : used.get(0);          // chưa có dòng counter = kỳ mới, chưa tiêu gì
        long need = t.getWaitingNeedMicro() == null ? 0 : t.getWaitingNeedMicro();
        return u + need <= b.getLimitMicroUsd();
    }

    // ------------------------------------------------------------------ agent mới
    /** Idempotent: gọi lại không tạo bản thứ hai (unique index). */
    @Transactional
    public void createDefaultAgentBudget(UUID agentId) {
        jdbc.update("INSERT INTO budget (scope_type, scope_id, period, limit_micro_usd, soft_pct) VALUES ('AGENT', ?, 'DAY', ?, ?) ON CONFLICT DO NOTHING",
                agentId, props.defaultAgentDayMicroUsd(), props.defaultSoftPct());
    }

    // ------------------------------------------------------------------ nội bộ
    private void requireScope(BudgetScope type, UUID scopeId) {
        if (type == BudgetScope.GLOBAL) {
            if (scopeId != null) throw new AppException(ErrorCode.VALIDATION, "GLOBAL không có scopeId");
            return;
        }
        if (scopeId == null) throw new AppException(ErrorCode.VALIDATION, "scopeId bắt buộc với " + type);
        String table = switch (type) {                       // hằng số, không nối từ đầu vào
            case DEPARTMENT -> "department";
            case AGENT -> "agent";
            case PROJECT -> "project";
            case GOAL -> "goal";
            case GLOBAL -> throw new IllegalStateException();
        };
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Long.class, scopeId);
        if (n == null || n == 0) throw new NotFoundException(table, scopeId);
    }

    private static Map<String, Object> auditMap(Budget b) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("budgetId", b.getId());
        d.put("scopeType", b.getScopeType());
        d.put("scopeId", b.getScopeId());
        d.put("period", b.getPeriod());
        d.put("limitMicroUsd", b.getLimitMicroUsd());
        return d;
    }
}
