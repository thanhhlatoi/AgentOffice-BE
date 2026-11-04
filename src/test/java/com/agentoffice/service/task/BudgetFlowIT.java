package com.agentoffice.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.domain.usage.BudgetPeriodType;
import com.agentoffice.domain.usage.BudgetScope;
import com.agentoffice.dto.usage.BudgetDto;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.entity.task.Task;
import com.agentoffice.entity.usage.Budget;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.service.usage.BudgetService;
import com.agentoffice.support.CoreIT;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Luồng đầu-cuối với FakeLlm, FakeAgentRunner và BudgetGuard THẬT: hết ngân sách -> WAITING_BUDGET -> tăng hạn mức -> SUCCEEDED.
 * (Dùng task lẻ thay vì goal vì Planner/PlanValidator chưa xong; luồng giống hệt.)
 *
 * Số liệu (giá mẫu cloud.mid = 3_000_000 / 15_000_000, maxOutputTokens 256, tiêu đề 2 ký tự):
 *   ước tính mỗi cuộc = 3 + 3840 = 3843; FakeLlm tốn thật 48. Hạn mức 3860 => cuộc 1 vừa (3843 <= 3860);
 *   cuộc 2: đã tiêu 48 + 3843 = 3891 > 3860 => bị chặn.
 */
class BudgetFlowIT extends CoreIT {
    @Autowired TaskScheduler scheduler;
    @Autowired TaskDispatcher dispatcher;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired BudgetService budgets;

    private Task queued(Agent a, String title) {
        Task t = taskService.create(new NewTask(null, null, a.getId(), "HUMAN", title, "x", Priority.NORMAL, 0));
        try { Thread.sleep(3); } catch (InterruptedException e) { throw new RuntimeException(e); }
        return taskService.settle(t.getId());
    }

    private TaskStatus status(Task t) { return tasks.findById(t.getId()).orElseThrow().getStatus(); }

    @Test void runsOutOfBudgetWaitsThenFinishesAfterTheOwnerRaisesTheLimit() {
        clock.set(Instant.parse("2026-03-10T03:00:00Z"));
        Agent a = agent("a");
        a.setModelProfile("cloud.mid");
        agentRepo.saveAndFlush(a);
        jdbc.update("UPDATE budget SET enabled = FALSE WHERE scope_type = 'GLOBAL'");
        jdbc.update("DELETE FROM budget WHERE scope_type = 'AGENT'");
        Budget b = budgets.create(new BudgetDto.Create(BudgetScope.AGENT, a.getId(), BudgetPeriodType.DAY, 3860L, null, null, null), "test");

        Task t1 = queued(a, "t1"), t2 = queued(a, "t2");

        List<Task> first = scheduler.pickRunnable(1);
        assertThat(first).extracting(Task::getId).containsExactly(t1.getId());
        dispatcher.runOne(t1.getId());
        assertThat(status(t1)).isEqualTo(TaskStatus.SUCCEEDED);

        scheduler.pickRunnable(1);
        dispatcher.runOne(t2.getId());
        Task blocked = tasks.findById(t2.getId()).orElseThrow();
        assertThat(blocked.getStatus()).as("hết ngân sách không phải FAILED").isEqualTo(TaskStatus.WAITING_BUDGET);
        assertThat(blocked.getAttempts()).isZero();
        assertThat(blocked.getWaitingBudgetId()).isEqualTo(b.getId());
        assertThat(scheduler.pickRunnable(5)).as("task đang chờ ngân sách không được chọn").isEmpty();

        budgets.raise(b.getId(), 10_000, "cho chạy tiếp", "tester");
        assertThat(status(t2)).isEqualTo(TaskStatus.QUEUED);

        scheduler.pickRunnable(1);
        dispatcher.runOne(t2.getId());
        assertThat(status(t2)).isEqualTo(TaskStatus.SUCCEEDED);

        assertThat(eventTypes()).containsSubsequence("task.succeeded", "budget.exceeded", "budget.resumed", "task.succeeded");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_record WHERE status = 'OK'", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT reserved_micro FROM budget_counter WHERE budget_id = ?", Long.class, b.getId()))
                .as("không rò chỗ đặt").isZero();
    }
}
