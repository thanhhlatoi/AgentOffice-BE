package com.agentoffice.service.task;

import com.agentoffice.domain.task.GoalStatus;
import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.entity.task.Task;
import com.agentoffice.repository.task.TaskRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.agentoffice.entity.agent.Agent;
import com.agentoffice.entity.event.DomainEvent;
import com.agentoffice.support.CoreIT;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * End-to-end: goal -> FakePlanner -> duyệt kế hoạch -> task chạy bằng FakeAgentRunner -> goal SUCCEEDED.
 * Phụ thuộc cả 4 lớp lõi của bạn nên
 */
@TestPropertySource(properties = {"agentoffice.scheduler.backoff-seconds=1,1,1", "agentoffice.scheduler.max-concurrent-tasks=4"})
class GoalFlowIT extends CoreIT {
    @Autowired GoalService goals;
    @Autowired OrchestratorService orchestrator;
    @Autowired TaskDispatcher dispatcher;
    @Autowired TaskRepository tasks;
    @Autowired TaskService taskService;

    private void runUntil(UUID goalId, GoalStatus target) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            dispatcher.tick();
            assertThat(goals.get(goalId).getStatus()).isEqualTo(target);
        });
    }

    @Test void happyPathEndsSucceededWithOrderedEvents() {
        agent("first"); agent("second");
        Goal g = goals.create("Lập báo cáo Q4", "Tổng hợp doanh thu", null, null);
        orchestrator.plan(g.getId(), List.of());
        assertThat(goals.get(g.getId()).getStatus()).isEqualTo(GoalStatus.AWAITING_PLAN_APPROVAL);
        assertThat(tasks.findByGoalIdOrderByCreatedAtAsc(g.getId())).as("chưa duyệt thì chưa có task").isEmpty();

        orchestrator.approvePlan(g.getId(), "owner", null);
        List<Task> created = tasks.findByGoalIdOrderByCreatedAtAsc(g.getId());
        assertThat(created).extracting(Task::getStatus).containsExactlyInAnyOrder(TaskStatus.QUEUED, TaskStatus.BLOCKED);

        runUntil(g.getId(), GoalStatus.SUCCEEDED);

        Goal done = goals.get(g.getId());
        assertThat(done.getResult()).contains("[fake]");
        assertThat(done.getFinishedAt()).isNotNull();
        assertThat(goals.detail(g.getId()).progressPercent()).isEqualTo(100);

        List<DomainEvent> evs = events();
        List<String> types = evs.stream().map(DomainEvent::getType).toList();
        assertThat(types).containsSubsequence("goal.created", "goal.plan_proposed", "approval.requested", "approval.resolved",
                "task.created", "task.created", "task.succeeded", "task.succeeded", "goal.succeeded");
        assertThat(types.get(types.size() - 1)).isEqualTo("goal.succeeded");
        assertThat(evs).extracting(DomainEvent::getId).isSorted().doesNotHaveDuplicates();

        Task first = created.get(0), second = created.get(1);
        assertThat(tasks.findById(second.getId()).orElseThrow().getStartedAt())
                .isAfterOrEqualTo(tasks.findById(first.getId()).orElseThrow().getFinishedAt());
    }

    @Test void aFailingTaskFailsTheGoalAndItsDependents() {
        Agent a = agent("only");
        Goal g = goals.create("Sẽ hỏng", "[fail] cố tình", null, null);
        orchestrator.plan(g.getId(), List.of());
        orchestrator.approvePlan(g.getId(), "owner", null);
        runUntil(g.getId(), GoalStatus.FAILED);
        assertThat(goals.get(g.getId()).getError()).contains("FAILED");
        assertThat(tasks.findByGoalIdOrderByCreatedAtAsc(g.getId())).allMatch(t -> t.getStatus().isTerminal());
        assertThat(eventTypes()).contains("goal.failed").doesNotContain("goal.succeeded");
    }

    @Test void transientErrorsRetryWithBackoffThenSucceed() {
        agent("flaky");
        Goal g = goals.create("Chập chờn", "[flaky:2] thử lại", null, null);
        orchestrator.plan(g.getId(), List.of());
        orchestrator.approvePlan(g.getId(), "owner", null);
        runUntil(g.getId(), GoalStatus.SUCCEEDED);
        Task flaky = tasks.findByGoalIdOrderByCreatedAtAsc(g.getId()).get(0);
        assertThat(flaky.getAttempts()).isEqualTo(2);
    }

    @Test void cancellingARunningTaskStopsItCooperatively() {
        Agent a = agent("slow");
        Task t = taskService.create(new NewTask(null, null, a.getId(), "HUMAN", "chậm", "[slow:5000] chậm", Priority.NORMAL, 0));
        taskService.settle(t.getId());
        taskService.transition(t.getId(), TaskStatus.RUNNING, "test");
        // dispatcher.runOne chạy ở luồng khác, ta huỷ giữa chừng
        Thread th = new Thread(() -> dispatcher.runOne(t.getId()));
        th.start();
        taskService.cancel(t.getId());
        try { th.join(10_000); } catch (InterruptedException e) { throw new RuntimeException(e); }
        assertThat(tasks.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }
}
