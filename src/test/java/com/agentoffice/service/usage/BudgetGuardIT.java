package com.agentoffice.service.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.domain.usage.BudgetExceededException;
import com.agentoffice.domain.usage.BudgetPeriodType;
import com.agentoffice.domain.usage.BudgetScope;
import com.agentoffice.domain.usage.CallContext;
import com.agentoffice.domain.usage.HardAction;
import com.agentoffice.dto.usage.BudgetDto;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.entity.usage.Budget;
import com.agentoffice.support.CoreIT;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * SPEC của BudgetGuard trên Postgres thật.
 * Mỗi test tự dựng ngân sách riêng: 2 dòng GLOBAL mẫu bị tắt và ngân sách AGENT mặc định bị xoá trong setUp.
 */
class BudgetGuardIT extends CoreIT {
    @Autowired BudgetGuard guard;
    @Autowired BudgetService budgets;

    private static final Instant DAY1 = Instant.parse("2026-03-10T03:00:00Z");   // 10:00 ngày 10/3 giờ Hồ Chí Minh
    private static final Instant DAY2 = Instant.parse("2026-03-11T03:00:00Z");
    private Agent agent;

    @BeforeEach void setUp() {
        clock.set(DAY1);
        agent = agent("a");
        jdbc.update("UPDATE budget SET enabled = FALSE WHERE scope_type = 'GLOBAL'");
        jdbc.update("DELETE FROM budget_counter");
        jdbc.update("DELETE FROM budget WHERE scope_type = 'AGENT'");
    }

    private CallContext ctx() { return ctxOf(agent, null, null, null); }

    private CallContext ctxOf(Agent a, UUID departmentId, UUID projectId, UUID goalId) {
        return new CallContext(a.getId(), null, goalId, projectId, departmentId, CallContext.Purpose.AGENT_RUN, null);
    }

    private Budget budget(BudgetScope scope, UUID scopeId, BudgetPeriodType period, long limit) {
        return budget(scope, scopeId, period, limit, 80, HardAction.BLOCK);
    }

    private Budget budget(BudgetScope scope, UUID scopeId, BudgetPeriodType period, long limit, int softPct, HardAction action) {
        return budgets.create(new BudgetDto.Create(scope, scopeId, period, limit, null, softPct, action), "test");
    }

    private Budget agentBudget(long limit) { return budget(BudgetScope.AGENT, agent.getId(), BudgetPeriodType.DAY, limit); }

    private Map<String, Object> counter(Budget b, LocalDate start) {
        return jdbc.queryForMap("SELECT * FROM budget_counter WHERE budget_id = ? AND period_start = ?", b.getId(), start);
    }

    private static final LocalDate D1 = LocalDate.of(2026, 3, 10);
    private static final LocalDate D2 = LocalDate.of(2026, 3, 11);

    private long count(String type) { return eventTypes().stream().filter(type::equals).count(); }

    // ---------------------------------------------------------------- nguyên tử
    @Test void fiftyThreadsOnlyTenFitAndNeverOverspend() throws Exception {
        Budget b = agentBudget(1000);                                   // đủ cho đúng 10 cuộc x 100
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < 50; i++) fs.add(pool.submit(() -> {
            go.await();
            try { guard.reserve(ctx(), 100); return true; } catch (BudgetExceededException e) { return false; }
        }));
        go.countDown();
        int ok = 0;
        for (var f : fs) if (f.get(30, TimeUnit.SECONDS)) ok++;
        pool.shutdown();
        assertThat(ok).isEqualTo(10);
        Map<String, Object> c = counter(b, D1);
        assertThat(c).containsEntry("reserved_micro", 1000L).containsEntry("spent_micro", 0L);
    }

    @Test void firstUseOfAFreshPeriodByManyThreadsDoesNotFail() throws Exception {
        Budget b = agentBudget(1_000_000);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 20; i++) fs.add(pool.submit(() -> { go.await(); return guard.reserve(ctx(), 1); }));
        go.countDown();
        for (var f : fs) f.get(30, TimeUnit.SECONDS);                   // ném nếu có lỗi trùng khoá counter
        pool.shutdown();
        assertThat(counter(b, D1)).containsEntry("reserved_micro", 20L);
    }

    // ---------------------------------------------------------------- settle / release
    @Test void settleMovesReservedToSpentAtTheActualAmount() {
        Budget b = agentBudget(1000);
        var r = guard.reserve(ctx(), 100);
        guard.settle(r, 60, 7);
        assertThat(counter(b, D1)).containsEntry("reserved_micro", 0L).containsEntry("spent_micro", 60L).containsEntry("spent_tokens", 7L);
    }

    @Test void settleAboveTheEstimateIsAcceptedEvenPastTheLimit() {
        Budget b = agentBudget(120);
        var r = guard.reserve(ctx(), 100);
        guard.settle(r, 150, 1);                                         // LLM tốn hơn ước tính: ghi nhận đủ, không ném lỗi
        assertThat(counter(b, D1)).containsEntry("reserved_micro", 0L).containsEntry("spent_micro", 150L);
        assertThatThrownBy(() -> guard.reserve(ctx(), 1)).isInstanceOf(BudgetExceededException.class);
    }

    @Test void releaseGivesTheReservationBackWithoutSpending() {
        Budget b = agentBudget(100);
        var r = guard.reserve(ctx(), 100);
        assertThatThrownBy(() -> guard.reserve(ctx(), 1)).isInstanceOf(BudgetExceededException.class);
        guard.release(r);
        assertThat(counter(b, D1)).containsEntry("reserved_micro", 0L).containsEntry("spent_micro", 0L);
        guard.reserve(ctx(), 100);                                       // chỗ đã trả nên đặt lại được
    }

    @Test void settleAfterThePeriodRolledOverStillHitsTheOriginalPeriod() {
        Budget b = agentBudget(1000);
        var r = guard.reserve(ctx(), 100);
        clock.set(DAY2);                                                 // LLM chạy lâu, qua nửa đêm
        guard.settle(r, 40, 1);
        assertThat(counter(b, D1)).containsEntry("reserved_micro", 0L).containsEntry("spent_micro", 40L);
    }

    // ---------------------------------------------------------------- chuỗi ngân sách
    @Test void failureLaterInTheChainRefundsEarlierReservations() {
        // GLOBAL rộng, AGENT hẹp: dù thứ tự id thế nào, GLOBAL không được rò chỗ đã đặt
        jdbc.update("UPDATE budget SET enabled = TRUE WHERE scope_type = 'GLOBAL' AND period = 'DAY'");
        jdbc.update("UPDATE budget SET limit_micro_usd = 10000 WHERE scope_type = 'GLOBAL' AND period = 'DAY'");
        Budget narrow = agentBudget(50);
        assertThatThrownBy(() -> guard.reserve(ctx(), 100)).isInstanceOfSatisfying(BudgetExceededException.class,
                e -> assertThat(e.budgetId()).isEqualTo(narrow.getId()));
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(reserved_micro), 0) FROM budget_counter", Long.class)).isZero();
    }

    @Test void failureEarlierInTheChainLeavesLaterBudgetsUntouched() {
        jdbc.update("UPDATE budget SET enabled = TRUE, limit_micro_usd = 50 WHERE scope_type = 'GLOBAL' AND period = 'DAY'");
        Budget wide = agentBudget(10_000);
        assertThatThrownBy(() -> guard.reserve(ctx(), 100)).isInstanceOf(BudgetExceededException.class);
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(reserved_micro), 0) FROM budget_counter", Long.class)).isZero();
        assertThat(wide.getId()).isNotNull();
    }

    @Test void onlyBudgetsThatApplyToTheCallAreChecked() {
        Agent other = agent("other");
        jdbc.update("DELETE FROM budget WHERE scope_type = 'AGENT'");       // bỏ ngân sách mặc định tự cấp cho agent mới
        budget(BudgetScope.AGENT, other.getId(), BudgetPeriodType.DAY, 1);          // của agent khác: không được chặn agent này
        Budget mine = agentBudget(1000);
        var r = guard.reserve(ctx(), 100);
        assertThat(r).hasSize(1);
        assertThat(r.get(0).budgetId()).isEqualTo(mine.getId());
    }

    @Test void departmentProjectAndGoalBudgetsJoinTheChainWhenTheCallCarriesThem() {
        UUID dep = jdbc.queryForObject("SELECT id FROM department WHERE name = 'Test'", UUID.class);
        UUID project = jdbc.queryForObject("INSERT INTO project(name) VALUES ('p') RETURNING id", UUID.class);
        UUID goal = jdbc.queryForObject("INSERT INTO goal(title) VALUES ('g') RETURNING id", UUID.class);
        budget(BudgetScope.DEPARTMENT, dep, BudgetPeriodType.DAY, 1000);
        budget(BudgetScope.PROJECT, project, BudgetPeriodType.MONTH, 1000);
        budget(BudgetScope.GOAL, goal, BudgetPeriodType.TOTAL, 1000);
        agentBudget(1000);
        assertThat(guard.reserve(ctxOf(agent, dep, project, goal), 10)).hasSize(4);
        assertThat(guard.reserve(ctx(), 10)).as("không mang project/goal/department thì chỉ còn AGENT").hasSize(1);
    }

    @Test void disabledBudgetIsIgnored() {
        Budget b = agentBudget(10);
        budgets.patch(b.getId(), new BudgetDto.Patch(null, null, false, null), "test");
        assertThat(guard.reserve(ctx(), 1000)).isEmpty();
    }

    // ---------------------------------------------------------------- cảnh báo
    @Test void softWarningFiresOncePerPeriod() {
        agentBudget(1000);                                               // mềm 80% = 800
        guard.settle(guard.reserve(ctx(), 100), 700, 1);
        assertThat(count("budget.warning")).isZero();
        guard.settle(guard.reserve(ctx(), 100), 100, 1);                 // spent = 800
        assertThat(count("budget.warning")).isEqualTo(1);
        guard.settle(guard.reserve(ctx(), 50), 50, 1);                   // spent = 850, đã cảnh báo rồi
        assertThat(count("budget.warning")).isEqualTo(1);
        clock.set(DAY2);                                                 // kỳ mới => được cảnh báo lại
        guard.settle(guard.reserve(ctx(), 100), 850, 1);
        assertThat(count("budget.warning")).isEqualTo(2);
    }

    @Test void hardLimitBlocksAndAnnouncesOncePerPeriod() {
        Budget b = agentBudget(100);
        guard.settle(guard.reserve(ctx(), 100), 100, 1);
        for (int i = 0; i < 3; i++) assertThatThrownBy(() -> guard.reserve(ctx(), 50)).isInstanceOf(BudgetExceededException.class);
        assertThat(count("budget.exceeded")).isEqualTo(1);
        assertThat(counter(b, D1)).containsEntry("hard_alerted", true).containsEntry("reserved_micro", 0L);
        clock.set(DAY2);
        guard.settle(guard.reserve(ctx(), 100), 100, 1);
        assertThatThrownBy(() -> guard.reserve(ctx(), 50)).isInstanceOf(BudgetExceededException.class);
        assertThat(count("budget.exceeded")).isEqualTo(2);
    }

    @Test void warnOnlyAllowsTheCallButStillAnnounces() {
        budget(BudgetScope.AGENT, agent.getId(), BudgetPeriodType.DAY, 50, 80, HardAction.WARN_ONLY);
        assertThat(guard.reserve(ctx(), 100)).hasSize(1);
        assertThat(count("budget.exceeded")).isEqualTo(1);
    }

    @Test void exceptionCarriesTheBlockingBudgetAndTheRefusedAmount() {
        Budget b = agentBudget(10);
        assertThatThrownBy(() -> guard.reserve(ctx(), 100)).isInstanceOfSatisfying(BudgetExceededException.class, e -> {
            assertThat(e.budgetId()).isEqualTo(b.getId());
            assertThat(e.scopeType()).isEqualTo(BudgetScope.AGENT);
            assertThat(e.scopeId()).isEqualTo(agent.getId());
            assertThat(e.neededMicroUsd()).isEqualTo(100);
        });
    }

    // ---------------------------------------------------------------- kỳ
    @Test void aNewDayStartsCountingFromZeroButTotalNeverResets() {
        Budget day = agentBudget(100);
        Budget total = budget(BudgetScope.AGENT, agent.getId(), BudgetPeriodType.TOTAL, 150);
        guard.settle(guard.reserve(ctx(), 100), 100, 1);
        clock.set(DAY2);
        assertThatThrownBy(() -> guard.reserve(ctx(), 100)).as("TOTAL còn 50").isInstanceOfSatisfying(BudgetExceededException.class,
                e -> assertThat(e.budgetId()).isEqualTo(total.getId()));
        guard.settle(guard.reserve(ctx(), 50), 50, 1);                   // ngày mới: DAY về 0 nên vừa đủ
        assertThat(counter(day, D2)).containsEntry("spent_micro", 50L);
        clock.set(Instant.parse("2027-06-01T03:00:00Z"));
        assertThatThrownBy(() -> guard.reserve(ctx(), 1)).as("TOTAL không bao giờ reset").isInstanceOf(BudgetExceededException.class);
    }
}
