package com.agentoffice.service.task;

import com.agentoffice.domain.task.AgentRunner;
import com.agentoffice.domain.task.GoalStatus;
import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.RunResult;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.entity.task.Task;
import com.agentoffice.repository.task.TaskRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import com.agentoffice.entity.agent.Agent;
import com.agentoffice.support.PlumbingIT;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

/** Dispatcher chuyển KẾT QUẢ của runner thành trạng thái task (runner, scheduler, state machine đều là mock). */
class TaskDispatcherIT extends PlumbingIT {
    @MockBean AgentRunner runner;
    @Autowired TaskDispatcher dispatcher;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired GoalService goals;

    @BeforeEach void backoff() { when(scheduler.backoffSeconds(anyInt())).thenReturn(5L); }

    private Task running(String input) {
        Agent a = agent("a-" + UUID.randomUUID());
        Task t = taskService.create(new NewTask(null, null, a.getId(), "HUMAN", "t", input, Priority.NORMAL, 0));
        taskService.settle(t.getId());
        taskService.transition(t.getId(), TaskStatus.RUNNING, "test");
        return tasks.findById(t.getId()).orElseThrow();
    }

    private Task reload(Task t) { return tasks.findById(t.getId()).orElseThrow(); }

    @Test void successCompletesWithTheOutput() {
        Task t = running("x");
        when(runner.run(any())).thenReturn(RunResult.succeeded("kết quả", 0));
        dispatcher.runOne(t.getId());
        assertThat(reload(t).getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(reload(t).getResult()).isEqualTo("kết quả");
    }

    @Test void budgetExceededMovesTheTaskToWaitingBudgetWithoutCountingAnAttempt() {
        Task t = running("x");
        UUID budgetId = jdbc.queryForObject("SELECT id FROM budget WHERE scope_type = 'GLOBAL' AND period = 'DAY'", UUID.class);
        when(runner.run(any())).thenThrow(new com.agentoffice.domain.usage.BudgetExceededException(
                budgetId, com.agentoffice.domain.usage.BudgetScope.GLOBAL, null, 777));
        dispatcher.runOne(t.getId());
        Task r = reload(t);
        assertThat(r.getStatus()).isEqualTo(TaskStatus.WAITING_BUDGET);
        assertThat(r.getAttempts()).isZero();
        assertThat(r.getWaitingBudgetId()).isEqualTo(budgetId);
        assertThat(r.getWaitingNeedMicro()).isEqualTo(777);
        assertThat(r.getError()).as("không phải lỗi").isNull();
    }

    @Test void permanentFailureFailsWithoutRetry() {
        Task t = running("x");
        when(runner.run(any())).thenReturn(RunResult.failed("sai logic"));
        dispatcher.runOne(t.getId());
        assertThat(reload(t).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(reload(t).getAttempts()).isZero();
    }

    @Test void transientFailureRequeuesWithBackoffAndCountsTheAttempt() {
        Task t = running("x");
        when(runner.run(any())).thenReturn(RunResult.retryable("429"));
        Instant before = Instant.now();
        dispatcher.runOne(t.getId());
        Task r = reload(t);
        assertThat(r.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(r.getAttempts()).isEqualTo(1);
        assertThat(r.getNextRunAt()).isAfter(before.plusSeconds(4));
        assertThat(r.getError()).contains("429");
    }

    @Test void transientFailureOnTheLastAttemptFails() {
        Task t = running("x");
        jdbc.update("UPDATE task SET attempts = 2 WHERE id = ?", t.getId());     // maxAttempts = 3
        when(runner.run(any())).thenReturn(RunResult.retryable("timeout"));
        dispatcher.runOne(t.getId());
        assertThat(reload(t).getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(reload(t).getError()).contains("timeout");
    }

    @Test void cancelRequestedBeforeStartCancelsWithoutRunning() {
        Task t = running("x");
        taskService.cancel(t.getId());                               // chỉ đặt cờ vì đang RUNNING
        dispatcher.runOne(t.getId());
        assertThat(reload(t).getStatus()).isEqualTo(TaskStatus.CANCELLED);
        org.mockito.Mockito.verifyNoInteractions(runner);
    }

    @Test void runnerObservingCancellationCancelsTheTask() {
        Task t = running("x");
        when(runner.run(any())).thenReturn(RunResult.cancelled());
        dispatcher.runOne(t.getId());
        assertThat(reload(t).getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test void waitingApprovalOutcomeParksTheTask() {
        Task t = running("x");
        when(runner.run(any())).thenReturn(RunResult.waitingApproval());
        dispatcher.runOne(t.getId());
        assertThat(reload(t).getStatus()).isEqualTo(TaskStatus.WAITING_APPROVAL);
    }

    @Test void handledOutcomeLeavesWhateverTheRunnerDid() {
        Task t = running("x");
        when(runner.run(any())).thenAnswer(inv -> {
            taskService.transition(t.getId(), TaskStatus.BLOCKED, "ask_agent");      // vd runner gọi ask_agent
            return RunResult.handled();
        });
        dispatcher.runOne(t.getId());
        assertThat(reload(t).getStatus()).isEqualTo(TaskStatus.BLOCKED);
    }

    @Test void ifTheRunnerChangedTheStatusTheDispatcherDoesNotFightIt() {
        Task t = running("x");
        when(runner.run(any())).thenAnswer(inv -> {
            taskService.transition(t.getId(), TaskStatus.CANCELLED, "goal cancelled");
            return RunResult.succeeded("muộn", 0);
        });
        dispatcher.runOne(t.getId());
        assertThat(reload(t).getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test void aCrashingRunnerFailsTheTaskInsteadOfKillingTheWorker() {
        Task t = running("x");
        when(runner.run(any())).thenThrow(new IllegalArgumentException("bí mật trong input"));
        dispatcher.runOne(t.getId());
        Task r = reload(t);
        assertThat(r.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(r.getError()).doesNotContain("bí mật");           // không rò nội dung ra lỗi
    }

    @Test void tokensAreChargedToTheGoal() {
        Agent a = agent("a");
        Goal g = goals.create("g", null, null, null);
        goals.move(g.getId(), GoalStatus.PLANNING);
        goals.move(g.getId(), GoalStatus.RUNNING);
        Task t = taskService.create(new NewTask(g.getId(), null, a.getId(), "ORCHESTRATOR", "t", "i", Priority.NORMAL, 0));
        taskService.settle(t.getId());
        taskService.transition(t.getId(), TaskStatus.RUNNING, "test");
        when(runner.run(any())).thenReturn(RunResult.succeeded("ok", 42));
        dispatcher.runOne(t.getId());
        assertThat(goals.get(g.getId()).getTokensUsed()).isEqualTo(42);
    }

    @Test void tickClaimsThroughTheSchedulerAndRunsOnThePool() {
        Task t = running("x");
        when(scheduler.pickRunnable(anyInt())).thenReturn(java.util.List.of(t));
        when(runner.run(any())).thenReturn(RunResult.succeeded("ok", 0));
        assertThat(dispatcher.tick()).isEqualTo(1);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(reload(t).getStatus()).isEqualTo(TaskStatus.SUCCEEDED));
    }
}
