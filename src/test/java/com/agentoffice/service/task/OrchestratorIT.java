package com.agentoffice.service.task;

import com.agentoffice.domain.approval.ApprovalKind;
import com.agentoffice.domain.approval.ApprovalStatus;
import com.agentoffice.domain.task.GoalStatus;
import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.dto.task.GoalDetailDto;
import com.agentoffice.entity.approval.Approval;
import com.agentoffice.entity.approval.ApprovalAudit;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.entity.task.Task;
import com.agentoffice.entity.task.TaskDependency;
import com.agentoffice.repository.approval.ApprovalAuditRepository;
import com.agentoffice.repository.approval.ApprovalRepository;
import com.agentoffice.repository.task.TaskDependencyRepository;
import com.agentoffice.repository.task.TaskRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentoffice.entity.agent.Agent;
import com.agentoffice.support.PlumbingIT;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Plumbing của OrchestratorService (PlanValidator/TaskStateMachine/TaskScheduler được mock). */
class OrchestratorIT extends PlumbingIT {
    @Autowired OrchestratorService orchestrator;
    @Autowired GoalService goals;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired TaskDependencyRepository deps;
    @Autowired ApprovalRepository approvals;
    @Autowired ApprovalAuditRepository audits;

    @BeforeEach void agents() { agent("first"); agent("second"); }

    private Goal planned() {
        Goal g = goals.create("Báo cáo", "mô tả", null, null);
        orchestrator.plan(g.getId(), List.of());
        return goals.get(g.getId());
    }

    @Test void planWaitsForApprovalWithoutCreatingTasks() {
        Goal g = planned();
        assertThat(g.getStatus()).isEqualTo(GoalStatus.AWAITING_PLAN_APPROVAL);
        assertThat(g.getPlan()).contains("analyze").contains("summarize");
        assertThat(tasks.countByGoalId(g.getId())).isZero();
        Approval a = approvals.findFirstByGoalIdAndKindAndStatus(g.getId(), ApprovalKind.PLAN, ApprovalStatus.PENDING).orElseThrow();
        assertThat(a.getPreview()).contains("Báo cáo").contains("analyze");
        assertThat(a.getTaskId()).isNull();
        assertThat(eventTypes()).containsSubsequence("goal.created", "goal.plan_proposed", "approval.requested");
        assertThat(audits.findByApprovalIdOrderByCreatedAtAsc(a.getId())).extracting(ApprovalAudit::getAction).containsExactly("REQUESTED");
    }

    @Test void approveCreatesTheTaskTreeWithDependenciesInOneGo() {
        Goal g = planned();
        orchestrator.approvePlan(g.getId(), "owner", "ok");
        assertThat(goals.get(g.getId()).getStatus()).isEqualTo(GoalStatus.RUNNING);
        List<Task> ts = tasks.findByGoalIdOrderByCreatedAtAsc(g.getId());
        assertThat(ts).hasSize(2).allMatch(t -> "ORCHESTRATOR".equals(t.getCreatedBy()));
        assertThat(ts).extracting(Task::getStatus).containsExactly(TaskStatus.QUEUED, TaskStatus.BLOCKED);
        assertThat(deps.findByTaskId(ts.get(1).getId())).extracting(TaskDependency::getDependsOnTaskId).containsExactly(ts.get(0).getId());
        assertThat(eventTypes()).contains("approval.resolved");
        Approval a = approvals.findAll().get(0);
        assertThat(a.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(audits.findByApprovalIdOrderByCreatedAtAsc(a.getId())).extracting(ApprovalAudit::getAction).containsExactly("REQUESTED", "APPROVED");
    }

    @Test void approvingTwiceIsAConflictAndCreatesNothingTwice() {
        Goal g = planned();
        orchestrator.approvePlan(g.getId(), "owner", null);
        assertThatThrownBy(() -> orchestrator.approvePlan(g.getId(), "owner", null)).isInstanceOf(IllegalStateException.class);
        assertThat(tasks.countByGoalId(g.getId())).isEqualTo(2);
    }

    @Test void expiredPlanCannotBeApproved() {
        Goal g = planned();
        jdbc.update("UPDATE approval SET expires_at = now() - interval '1 minute'");
        assertThatThrownBy(() -> orchestrator.approvePlan(g.getId(), "owner", null)).isInstanceOf(IllegalStateException.class);
        assertThat(goals.get(g.getId()).getStatus()).isEqualTo(GoalStatus.AWAITING_PLAN_APPROVAL);   // rollback giữ nguyên
    }

    @Test void expirePlansFailsTheGoal() {
        Goal g = planned();
        jdbc.update("UPDATE approval SET expires_at = now() - interval '1 minute'");
        assertThat(orchestrator.expirePlans()).isEqualTo(1);
        assertThat(goals.get(g.getId()).getStatus()).isEqualTo(GoalStatus.FAILED);
        assertThat(approvals.findAll().get(0).getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
    }

    @Test void rejectionReplansTwiceThenFailsTheGoal() {
        Goal g = planned();
        for (int i = 1; i <= 2; i++) {
            assertThat(orchestrator.rejectPlan(g.getId(), "owner", "chưa ổn " + i).replan()).isTrue();
            assertThat(goals.get(g.getId()).getStatus()).isEqualTo(GoalStatus.PLANNING);
            orchestrator.plan(g.getId(), List.of("chưa ổn " + i));
            assertThat(goals.get(g.getId()).getStatus()).isEqualTo(GoalStatus.AWAITING_PLAN_APPROVAL);
        }
        assertThat(orchestrator.rejectPlan(g.getId(), "owner", "vẫn không").replan()).isFalse();
        assertThat(goals.get(g.getId()).getStatus()).isEqualTo(GoalStatus.FAILED);
        assertThat(approvals.findAll()).extracting(Approval::getStatus).containsOnly(ApprovalStatus.REJECTED);
        assertThat(approvals.findAll()).anyMatch(a -> "chưa ổn 1".equals(a.getReason()));
    }

    @Test void invalidPlansAreRetriedTwiceThenTheGoalFails() {
        when(validator.validate(any(), any())).thenReturn(List.of("key 'x' sai"));
        Goal g = goals.create("g", null, null, null);
        orchestrator.plan(g.getId(), List.of());
        verify(validator, times(3)).validate(any(), any());
        Goal after = goals.get(g.getId());
        assertThat(after.getStatus()).isEqualTo(GoalStatus.FAILED);
        assertThat(after.getError()).contains("key 'x' sai");
        assertThat(after.getPlanAttempts()).isEqualTo(3);
        assertThat(eventTypes()).contains("goal.failed");
    }

    @Test void secondAttemptCanSucceed() {
        when(validator.validate(any(), any())).thenReturn(List.of("lỗi")).thenReturn(List.of());
        Goal g = goals.create("g", null, null, null);
        orchestrator.plan(g.getId(), List.of());
        assertThat(goals.get(g.getId()).getStatus()).isEqualTo(GoalStatus.AWAITING_PLAN_APPROVAL);
    }

    @Test void cancelGoalClosesThePendingPlanApproval() {
        Goal g = planned();
        orchestrator.cancelGoal(g.getId(), "owner");
        assertThat(goals.get(g.getId()).getStatus()).isEqualTo(GoalStatus.CANCELLED);
        assertThat(approvals.findAll().get(0).getStatus()).isEqualTo(ApprovalStatus.REJECTED);
    }

    // ---- tổng hợp kết quả
    private Goal runningGoalWith(int n) {
        Agent a = agent("w" + UUID.randomUUID(), 5);
        Goal g = goals.create("g", null, null, null);
        goals.move(g.getId(), GoalStatus.PLANNING);
        goals.move(g.getId(), GoalStatus.RUNNING);
        for (int i = 0; i < n; i++) {
            Task t = taskService.create(new NewTask(g.getId(), null, a.getId(), "ORCHESTRATOR", "task" + i, "in", Priority.NORMAL, 0));
            taskService.settle(t.getId());
            taskService.transition(t.getId(), TaskStatus.RUNNING, "test");
        }
        return g;
    }

    @Test void goalSucceedsWhenAllTasksSucceedWithAnAggregatedResult() {
        Goal g = runningGoalWith(2);
        List<Task> ts = tasks.findByGoalIdOrderByCreatedAtAsc(g.getId());
        taskService.complete(ts.get(0).getId(), "kết quả A");
        assertThat(goals.get(g.getId()).getStatus()).isEqualTo(GoalStatus.RUNNING);
        taskService.complete(ts.get(1).getId(), "kết quả B");
        Goal done = goals.get(g.getId());
        assertThat(done.getStatus()).isEqualTo(GoalStatus.SUCCEEDED);
        assertThat(done.getResult()).contains("task0").contains("kết quả A").contains("kết quả B");
        assertThat(eventTypes()).endsWith("goal.succeeded");
    }

    @Test void goalFailsOnlyAfterEveryTaskIsTerminal() {
        Goal g = runningGoalWith(2);
        List<Task> ts = tasks.findByGoalIdOrderByCreatedAtAsc(g.getId());
        taskService.fail(ts.get(0).getId(), "hỏng");
        assertThat(goals.get(g.getId()).getStatus()).isEqualTo(GoalStatus.RUNNING);
        taskService.complete(ts.get(1).getId(), "ok");
        assertThat(goals.get(g.getId()).getStatus()).isEqualTo(GoalStatus.FAILED);
        assertThat(goals.get(g.getId()).getError()).contains("task0");
    }

    @Test void twoTasksFinishingAtTheSameTimeStillCompleteTheGoalExactlyOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            Goal g = runningGoalWith(2);
            List<Task> ts = tasks.findByGoalIdOrderByCreatedAtAsc(g.getId());
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (Task t : ts) fs.add(pool.submit(() -> { go.await(); taskService.complete(t.getId(), "r"); return null; }));
            go.countDown();
            for (var f : fs) f.get(20, TimeUnit.SECONDS);
            pool.shutdown();
            assertThat(goals.get(g.getId()).getStatus()).as("vòng %d", round).isEqualTo(GoalStatus.SUCCEEDED);
        }
        assertThat(eventTypes().stream().filter("goal.succeeded"::equals).count()).isEqualTo(5);
    }

    @Test void exceedingTheGoalTokenBudgetStopsTheGoal() {
        Agent a = agent("w", 5);
        Goal g = goals.create("g", null, null, 100L);
        goals.move(g.getId(), GoalStatus.PLANNING);
        goals.move(g.getId(), GoalStatus.RUNNING);
        Task t = taskService.create(new NewTask(g.getId(), null, a.getId(), "ORCHESTRATOR", "t", "i", Priority.NORMAL, 0));
        taskService.settle(t.getId());
        goals.recordTokens(g.getId(), 60);
        assertThat(goals.get(g.getId()).getStatus()).isEqualTo(GoalStatus.RUNNING);
        goals.recordTokens(g.getId(), 50);
        Goal after = goals.get(g.getId());
        assertThat(after.getStatus()).isEqualTo(GoalStatus.FAILED);
        assertThat(after.getTokensUsed()).isEqualTo(110);
        assertThat(after.getError()).contains("ngân sách");
        assertThat(tasks.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test void goalDetailBuildsTheTreeAndProgress() {
        Agent a = agent("w", 5);
        Goal g = goals.create("g", null, null, null);
        goals.move(g.getId(), GoalStatus.PLANNING);
        goals.move(g.getId(), GoalStatus.RUNNING);
        Task root = taskService.create(new NewTask(g.getId(), null, a.getId(), "ORCHESTRATOR", "root", "i", Priority.NORMAL, 0));
        Task kid = taskService.create(new NewTask(g.getId(), root.getId(), a.getId(), "ORCHESTRATOR", "kid", "i", Priority.NORMAL, 1));
        taskService.settle(root.getId());
        taskService.settle(kid.getId());
        taskService.transition(kid.getId(), TaskStatus.RUNNING, "t");
        taskService.complete(kid.getId(), "x");
        GoalDetailDto d = goals.detail(g.getId());
        assertThat(d.tasks()).hasSize(1);
        assertThat(d.tasks().get(0).children()).hasSize(1);
        assertThat(d.progressPercent()).isEqualTo(50);
    }
}
