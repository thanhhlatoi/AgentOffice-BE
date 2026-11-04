package com.agentoffice.service.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.domain.usage.BudgetPeriodType;
import com.agentoffice.domain.usage.BudgetScope;
import com.agentoffice.domain.usage.HardAction;
import com.agentoffice.dto.usage.BudgetDto;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.entity.task.Task;
import com.agentoffice.entity.usage.Budget;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.service.task.TaskService;
import com.agentoffice.support.PostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/** Phần của tôi: CRUD, raise, tự chạy tiếp, agent mới. Không dùng BudgetGuard thật nên xanh dù bạn chưa viết nó. */
class BudgetServiceIT extends PostgresIT {
    @Autowired BudgetService budgets;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;

    private static final Instant DAY1 = Instant.parse("2026-03-10T03:00:00Z");
    private static final Instant DAY2 = Instant.parse("2026-03-11T03:00:00Z");

    @BeforeEach void setUp() { clock.set(DAY1); }

    private Budget create(BudgetScope s, UUID id, BudgetPeriodType p, long limit) {
        return budgets.create(new BudgetDto.Create(s, id, p, limit, null, null, null), "test");
    }

    private Task waiting(Agent a, Budget b, long need) {
        Task t = taskService.create(new NewTask(null, null, a.getId(), "HUMAN", "t", "in", Priority.NORMAL, 0));
        taskService.settle(t.getId());
        taskService.transition(t.getId(), TaskStatus.RUNNING, "test");
        return taskService.waitForBudget(t.getId(), b.getId(), need);
    }

    private Task reload(Task t) { return tasks.findById(t.getId()).orElseThrow(); }

    private void spend(Budget b, String periodStart, long spent) {
        jdbc.update("INSERT INTO budget_counter(budget_id, period_start, spent_micro) VALUES (?, ?::date, ?)", b.getId(), periodStart, spent);
    }

    // ---------------------------------------------------------------- agent mới
    @Test void newAgentGetsADepartmentAndADefaultDailyBudgetExactlyOnce() {
        Agent a = agent("a");
        UUID dep = jdbc.queryForObject("SELECT department_id FROM agent WHERE id = ?", UUID.class, a.getId());
        assertThat(dep).isNotNull();
        assertThat(jdbc.queryForObject("SELECT name FROM department WHERE id = ?", String.class, dep)).isEqualTo("Test");
        assertThat(jdbc.queryForObject("SELECT limit_micro_usd FROM budget WHERE scope_type = 'AGENT' AND scope_id = ? AND period = 'DAY'",
                Long.class, a.getId())).isEqualTo(1_000_000L);
        budgets.createDefaultAgentBudget(a.getId());                     // gọi lại: không tạo bản thứ hai
        assertThat(budgets.list(BudgetScope.AGENT, a.getId())).hasSize(1);
        assertThat(agentRepo.findById(a.getId()).orElseThrow().getDepartmentId()).isEqualTo(dep);
    }

    // ---------------------------------------------------------------- CRUD
    @Test void scopeMustExistAndGlobalHasNoScopeId() {
        assertThatThrownBy(() -> create(BudgetScope.AGENT, UUID.randomUUID(), BudgetPeriodType.DAY, 10)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> create(BudgetScope.AGENT, null, BudgetPeriodType.DAY, 10)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> create(BudgetScope.GLOBAL, UUID.randomUUID(), BudgetPeriodType.DAY, 10)).isInstanceOf(AppException.class);
    }

    @Test void oneBudgetPerScopeAndPeriod() {
        assertThatThrownBy(() -> create(BudgetScope.GLOBAL, null, BudgetPeriodType.DAY, 10))      // đã có bản mẫu từ V10
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        create(BudgetScope.GLOBAL, null, BudgetPeriodType.TOTAL, 10);
    }

    @Test void patchChangesEverythingExceptTheLimit() {
        Budget b = create(BudgetScope.GLOBAL, null, BudgetPeriodType.TOTAL, 500);
        Budget p = budgets.patch(b.getId(), new BudgetDto.Patch(50, HardAction.WARN_ONLY, false, 999L), "test");
        assertThat(p.getSoftPct()).isEqualTo(50);
        assertThat(p.getHardAction()).isEqualTo(HardAction.WARN_ONLY);
        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getLimitMicroUsd()).isEqualTo(500);
    }

    @Test void deleteRefusedWhileTasksWaitOnItOrWhenItHasHistory() {
        Agent a = agent("a");
        Budget b = create(BudgetScope.GLOBAL, null, BudgetPeriodType.TOTAL, 100);
        Task t = waiting(a, b, 50);
        assertThatThrownBy(() -> budgets.delete(b.getId(), "test")).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.CONFLICT));
        budgets.raise(b.getId(), 1000, "đủ rồi", "test");                // task chạy tiếp
        assertThat(reload(t).getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThatThrownBy(() -> budgets.delete(b.getId(), "test")).as("đã có lịch sử").isInstanceOf(AppException.class);

        Budget plain = create(BudgetScope.AGENT, a.getId(), BudgetPeriodType.TOTAL, 100);
        budgets.delete(plain.getId(), "test");
        assertThatThrownBy(() -> budgets.get(plain.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test void statusReportsSpentReservedRemainingAndPercent() {
        Budget b = create(BudgetScope.GLOBAL, null, BudgetPeriodType.TOTAL, 1000);
        jdbc.update("INSERT INTO budget_counter(budget_id, period_start, spent_micro, reserved_micro, spent_tokens) VALUES (?, '1970-01-01', 300, 200, 42)", b.getId());
        var s = budgets.status(b.getId());
        assertThat(s.spentMicroUsd()).isEqualTo(300);
        assertThat(s.reservedMicroUsd()).isEqualTo(200);
        assertThat(s.remainingMicroUsd()).isEqualTo(500);
        assertThat(s.usedPct()).isEqualTo(50);
        assertThat(s.spentTokens()).isEqualTo(42);
    }

    // ---------------------------------------------------------------- raise
    @Test void raiseOnlyIncreasesAndRecordsAnImmutableChange() {
        Budget b = create(BudgetScope.GLOBAL, null, BudgetPeriodType.TOTAL, 100);
        assertThatThrownBy(() -> budgets.raise(b.getId(), 100, "x", "test")).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.code()).isEqualTo(ErrorCode.UNPROCESSABLE));
        assertThatThrownBy(() -> budgets.raise(b.getId(), 50, "x", "test")).isInstanceOf(AppException.class);
        budgets.raise(b.getId(), 300, "dự án gấp", "tester");
        assertThat(budgets.get(b.getId()).getLimitMicroUsd()).isEqualTo(300);
        var ch = budgets.changes(b.getId());
        assertThat(ch).hasSize(1);
        assertThat(ch.get(0).getOldLimit()).isEqualTo(100);
        assertThat(ch.get(0).getNewLimit()).isEqualTo(300);
        assertThat(ch.get(0).getChangedBy()).isEqualTo("tester");
        assertThat(ch.get(0).getReason()).isEqualTo("dự án gấp");
        assertThatThrownBy(() -> jdbc.update("UPDATE budget_change SET reason = 'sửa lén'")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM budget_change")).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permission_audit WHERE action = 'BUDGET_RAISE'", Long.class)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- tự chạy tiếp
    @Test void raisingTheLimitResumesTheWaitingTaskKeepingItsAttempts() {
        Agent a = agent("a");
        Budget b = create(BudgetScope.GLOBAL, null, BudgetPeriodType.TOTAL, 100);
        spend(b, "1970-01-01", 100);
        Task t = waiting(a, b, 50);
        assertThat(reload(t).getStatus()).isEqualTo(TaskStatus.WAITING_BUDGET);
        assertThat(budgets.resumeEligible()).as("chưa tăng hạn mức").isZero();

        budgets.raise(b.getId(), 1000, "tăng", "test");

        Task r = reload(t);
        assertThat(r.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(r.getAttempts()).isZero();
        assertThat(r.getWaitingBudgetId()).isNull();
        assertThat(eventTypes()).containsSubsequence("task.created", "task.state_changed", "task.state_changed", "budget.resumed");
    }

    @Test void raisingByTooLittleLeavesTheTaskWaiting() {
        Agent a = agent("a");
        Budget b = create(BudgetScope.GLOBAL, null, BudgetPeriodType.TOTAL, 100);
        spend(b, "1970-01-01", 100);
        Task t = waiting(a, b, 50);
        budgets.raise(b.getId(), 120, "ít quá", "test");                 // còn 20 < cần 50
        assertThat(reload(t).getStatus()).isEqualTo(TaskStatus.WAITING_BUDGET);
        budgets.raise(b.getId(), 150, "đủ", "test");
        assertThat(reload(t).getStatus()).isEqualTo(TaskStatus.QUEUED);
    }

    @Test void aNewPeriodResumesTheTaskWithoutAnyoneRaisingTheLimit() {
        Agent a = agent("a");
        Budget b = budgets.list(BudgetScope.AGENT, a.getId()).get(0);      // ngân sách AGENT/DAY mặc định 1_000_000
        spend(b, "2026-03-10", 1_000_000);
        Task t = waiting(a, b, 50);
        assertThat(budgets.resumeEligible()).isZero();
        clock.set(DAY2);
        assertThat(budgets.resumeEligible()).isEqualTo(1);
        assertThat(reload(t).getStatus()).isEqualTo(TaskStatus.QUEUED);
    }

    @Test void disablingOrWarnOnlyAlsoLetsWaitingTasksGo() {
        Agent a = agent("a");
        Budget b1 = create(BudgetScope.GLOBAL, null, BudgetPeriodType.TOTAL, 100);
        spend(b1, "1970-01-01", 100);
        Task t1 = waiting(a, b1, 50);
        budgets.patch(b1.getId(), new BudgetDto.Patch(null, null, false, null), "test");
        assertThat(reload(t1).getStatus()).isEqualTo(TaskStatus.QUEUED);

        Budget b2 = create(BudgetScope.AGENT, a.getId(), BudgetPeriodType.TOTAL, 100);
        spend(b2, "1970-01-01", 100);
        Task t2 = waiting(a, b2, 50);
        budgets.patch(b2.getId(), new BudgetDto.Patch(null, HardAction.WARN_ONLY, null, null), "test");
        assertThat(reload(t2).getStatus()).isEqualTo(TaskStatus.QUEUED);
    }
}
