package com.agentoffice.service.task;

import com.agentoffice.domain.task.GoalStatus;
import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.entity.task.Task;
import com.agentoffice.repository.task.TaskRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.agentoffice.entity.agent.Agent;
import com.agentoffice.exception.UnprocessableException;
import com.agentoffice.support.PlumbingIT;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Plumbing của TaskService (state machine/scheduler được mock). */
class TaskServiceIT extends PlumbingIT {
    @Autowired TaskService service;
    @Autowired TaskRepository tasks;
    @Autowired GoalService goalService;
    @Autowired PlatformTransactionManager txm;

    private NewTask nt(Agent a, UUID goal, UUID parent, int depth) {
        return new NewTask(goal, parent, a.getId(), "HUMAN", "t", "in", Priority.NORMAL, depth);
    }

    private Task running(Agent a) {
        Task t = service.create(nt(a, null, null, 0));
        service.settle(t.getId());
        service.transition(t.getId(), TaskStatus.RUNNING, "test");
        return tasks.findById(t.getId()).orElseThrow();
    }

    // ---- giới hạn khi tạo
    @Test void rejectsOfflineAgent() {
        Agent a = agent("off");
        jdbc.update("UPDATE agent SET status='OFFLINE' WHERE id=?", a.getId());
        assertThatThrownBy(() -> service.create(nt(a, null, null, 0))).isInstanceOf(UnprocessableException.class);
    }

    @Test void rejectsDepthBeyondThree() {
        Agent a = agent("a");
        service.create(nt(a, null, null, 3));
        assertThatThrownBy(() -> service.create(nt(a, null, null, 4))).isInstanceOf(UnprocessableException.class).hasMessageContaining("3");
    }

    @Test void rejectsMoreThanTwentyTasksPerGoal() {
        Agent a = agent("a");
        Goal g = goalService.create("g", null, null, null);
        for (int i = 0; i < 20; i++) service.create(nt(a, g.getId(), null, 0));
        assertThatThrownBy(() -> service.create(nt(a, g.getId(), null, 0))).isInstanceOf(UnprocessableException.class).hasMessageContaining("20");
        assertThat(tasks.countByGoalId(g.getId())).isEqualTo(20);
    }

    @Test void rejectsTasksForFinishedGoal() {
        Agent a = agent("a");
        Goal g = goalService.create("g", null, null, null);
        goalService.cancel(g.getId());
        assertThatThrownBy(() -> service.create(nt(a, g.getId(), null, 0))).isInstanceOf(UnprocessableException.class);
    }

    @Test void selfDependencyIsRejected() {
        Task t = service.create(nt(agent("a"), null, null, 0));
        assertThatThrownBy(() -> service.addDependency(t.getId(), t.getId())).isInstanceOf(UnprocessableException.class);
    }

    @Test void createEmitsTaskCreated() {
        service.create(nt(agent("a"), null, null, 0));
        assertThat(eventTypes()).containsExactly("task.created");
    }

    // ---- transition
    @Test void transitionAsksTheStateMachineAndEmitsEventsInOrder() {
        Task t = service.create(nt(agent("a"), null, null, 0));
        service.transition(t.getId(), TaskStatus.QUEUED, "r");
        verify(stateMachine).require(TaskStatus.PENDING, TaskStatus.QUEUED);
        assertThat(eventTypes()).containsExactly("task.created", "task.state_changed");
        assertThat(tasks.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.QUEUED);
    }

    @Test void illegalTransitionChangesNothingAndEmitsNothing() {
        Task t = service.create(nt(agent("a"), null, null, 0));
        doThrow(new IllegalStateException("no")).when(stateMachine).require(any(), any());
        assertThatThrownBy(() -> service.transition(t.getId(), TaskStatus.RUNNING, "r")).isInstanceOf(IllegalStateException.class);
        assertThat(tasks.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(eventTypes()).containsExactly("task.created");
    }

    @Test void rollbackAfterTransitionLeavesNeitherStatusNorEvent() {
        Task t = service.create(nt(agent("a"), null, null, 0));
        assertThatThrownBy(() -> new TransactionTemplate(txm).executeWithoutResult(s -> {
            service.transition(t.getId(), TaskStatus.QUEUED, "r");
            throw new IllegalArgumentException("sau đó có lỗi");
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(tasks.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(eventTypes()).containsExactly("task.created");
    }

    @Test void terminalStatesEmitSpecificEventsAndTimestamps() {
        Agent a = agent("a");
        Task ok = running(a);
        assertThat(tasks.findById(ok.getId()).orElseThrow().getStartedAt()).isNotNull();
        service.complete(ok.getId(), "xong");
        Task done = tasks.findById(ok.getId()).orElseThrow();
        assertThat(done.getResult()).isEqualTo("xong");
        assertThat(done.getFinishedAt()).isNotNull();
        Task bad = running(a);
        service.fail(bad.getId(), "hỏng");
        assertThat(eventTypes()).contains("task.succeeded", "task.failed");
        assertThat(tasks.findById(bad.getId()).orElseThrow().getError()).isEqualTo("hỏng");
    }

    @Test void startedAtIsKeptOnSecondRun() {
        Task t = running(agent("a"));
        var first = tasks.findById(t.getId()).orElseThrow().getStartedAt();
        service.scheduleRetry(t.getId(), java.time.Instant.now(), "e");
        service.transition(t.getId(), TaskStatus.RUNNING, "again");
        assertThat(tasks.findById(t.getId()).orElseThrow().getStartedAt()).isEqualTo(first);
        assertThat(tasks.findById(t.getId()).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test void taskVersionDetectsLostUpdates() {
        Task t = service.create(nt(agent("a"), null, null, 0));
        Task a = tasks.findById(t.getId()).orElseThrow();
        Task b = tasks.findById(t.getId()).orElseThrow();
        a.setPriority(Priority.HIGH);
        tasks.saveAndFlush(a);
        b.setPriority(Priority.LOW);
        assertThatThrownBy(() -> tasks.saveAndFlush(b)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    // ---- settle
    @Test void settleQueuesWithoutDependenciesAndBlocksWithUnfinishedOnes() {
        Agent a = agent("a");
        Task free = service.create(nt(a, null, null, 0));
        assertThat(service.settle(free.getId()).getStatus()).isEqualTo(TaskStatus.QUEUED);
        Task waiting = service.create(nt(a, null, null, 0));
        service.addDependency(waiting.getId(), free.getId());
        assertThat(service.settle(waiting.getId()).getStatus()).isEqualTo(TaskStatus.BLOCKED);
        Task after = service.create(nt(a, null, null, 0));
        service.addDependency(after.getId(), free.getId());
        service.transition(free.getId(), TaskStatus.RUNNING, "t");
        service.complete(free.getId(), "x");
        assertThat(service.settle(after.getId()).getStatus()).isEqualTo(TaskStatus.QUEUED);
    }

    // ---- huỷ hợp tác
    @Test void cancelQueuedTaskIsImmediateAndCascadesToUnstartedChildren() {
        Agent a = agent("a");
        Task parent = service.create(nt(a, null, null, 0));
        service.settle(parent.getId());
        Task child = service.create(nt(a, null, parent.getId(), 1));
        service.settle(child.getId());
        Task grand = service.create(nt(a, null, child.getId(), 2));
        service.settle(grand.getId());
        service.cancel(parent.getId());
        for (Task t : List.of(parent, child, grand)) assertThat(tasks.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(eventTypes()).filteredOn("task.cancelled"::equals).hasSize(3);
    }

    @Test void cancelRunningTaskOnlySetsTheFlag() {
        Task t = running(agent("a"));
        service.cancel(t.getId());
        Task after = tasks.findById(t.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(after.isCancelRequested()).isTrue();
    }

    @Test void cancelFinishedTaskIsAConflict() {
        Agent a = agent("a");
        Task t = running(a);
        service.complete(t.getId(), "x");
        assertThatThrownBy(() -> service.cancel(t.getId())).isInstanceOf(IllegalStateException.class);
    }

    @Test void cancellingAGoalCancelsOpenTasksAndFlagsRunningOnes() {
        Agent a = agent("a");
        Goal g = goalService.create("g", null, null, null);
        goalService.move(g.getId(), GoalStatus.PLANNING);
        goalService.move(g.getId(), GoalStatus.RUNNING);
        Task q = service.create(nt(a, g.getId(), null, 0));
        service.settle(q.getId());
        Task r = service.create(nt(a, g.getId(), null, 0));
        service.settle(r.getId());
        service.transition(r.getId(), TaskStatus.RUNNING, "t");
        goalService.cancel(g.getId());
        assertThat(tasks.findById(q.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(tasks.findById(r.getId()).orElseThrow().isCancelRequested()).isTrue();
        assertThat(eventTypes()).contains("goal.cancelled");
    }

    // ---- thử lại thủ công / ưu tiên / tìm kiếm
    @Test void manualRetryClonesAFailedTaskAndRejectsOthers() {
        Task t = running(agent("a"));
        assertThatThrownBy(() -> service.retry(t.getId())).isInstanceOf(IllegalStateException.class);   // chưa FAILED
        service.fail(t.getId(), "x");
        Task copy = service.retry(t.getId());
        assertThat(copy.getId()).isNotEqualTo(t.getId());
        assertThat(copy.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(copy.getAttempts()).isZero();
    }

    @Test void changePriorityRejectsFinishedTask() {
        Task t = running(agent("a"));
        assertThat(service.changePriority(t.getId(), Priority.HIGH).getPriority()).isEqualTo(Priority.HIGH);
        service.complete(t.getId(), "x");
        assertThatThrownBy(() -> service.changePriority(t.getId(), Priority.LOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test void searchFiltersAndPages() {
        Agent a = agent("a"), b = agent("b");
        for (int i = 0; i < 3; i++) service.settle(service.create(nt(a, null, null, 0)).getId());
        service.create(nt(b, null, null, 0));
        assertThat(service.search(null, a.getId(), null, 0, 2).getContent()).hasSize(2);
        assertThat(service.search(null, a.getId(), null, 0, 2).getTotalElements()).isEqualTo(3);
        assertThat(service.search(TaskStatus.PENDING, null, null, 0, 10).getTotalElements()).isEqualTo(1);
        assertThat(service.search(TaskStatus.QUEUED, b.getId(), null, 0, 10).getTotalElements()).isZero();
    }
}
