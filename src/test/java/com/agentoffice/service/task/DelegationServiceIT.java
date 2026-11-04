package com.agentoffice.service.task;

import com.agentoffice.domain.task.GoalStatus;
import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.entity.task.Task;
import com.agentoffice.entity.task.TaskDependency;
import com.agentoffice.repository.task.TaskDependencyRepository;
import com.agentoffice.repository.task.TaskRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.entity.agent.Agent;
import com.agentoffice.exception.UnprocessableException;
import com.agentoffice.entity.event.DomainEvent;
import com.agentoffice.support.PlumbingIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Các bảo vệ của delegate_task/ask_agent (không phụ thuộc state machine thật). */
class DelegationServiceIT extends PlumbingIT {
    @Autowired DelegationService delegation;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired TaskDependencyRepository deps;
    @Autowired GoalService goals;

    private Task running(Agent a, UUID goalId, UUID parent, int depth) {
        Task t = taskService.create(new NewTask(goalId, parent, a.getId(), "HUMAN", "t", "in", Priority.NORMAL, depth));
        taskService.settle(t.getId());
        taskService.transition(t.getId(), TaskStatus.RUNNING, "test");
        return tasks.findById(t.getId()).orElseThrow();
    }

    @Test void delegateCreatesAQueuedChildAndEmitsHandoff() {
        Agent a = agent("a"), b = agent("b");
        Task parent = running(a, null, null, 0);
        Task child = delegation.delegateTask(parent.getId(), b.getId(), "việc con", "làm đi", Priority.HIGH);
        assertThat(child.getParentTaskId()).isEqualTo(parent.getId());
        assertThat(child.getAssigneeAgentId()).isEqualTo(b.getId());
        assertThat(child.getDepth()).isEqualTo(1);
        assertThat(child.getCreatedBy()).isEqualTo(a.getId().toString());
        assertThat(child.getPriority()).isEqualTo(Priority.HIGH);
        assertThat(child.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(tasks.findById(parent.getId()).orElseThrow().getStatus()).as("giao việc không chờ").isEqualTo(TaskStatus.RUNNING);
        DomainEvent h = events().stream().filter(e -> e.getType().equals("task.handoff")).findFirst().orElseThrow();
        assertThat(h.getPayload()).contains(a.getId().toString()).contains(b.getId().toString()).contains(child.getId().toString()).contains("occurredAt");
    }

    @Test void blocksAToBToALoop() {
        Agent a = agent("a"), b = agent("b");
        Task ta = running(a, null, null, 0);
        Task tb = delegation.delegateTask(ta.getId(), b.getId(), "x", "y", null);
        taskService.transition(tb.getId(), TaskStatus.RUNNING, "test");
        assertThatThrownBy(() -> delegation.delegateTask(tb.getId(), a.getId(), "back", "y", null))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("Vòng lặp");
    }

    @Test void blocksDelegatingToSelf() {
        Agent a = agent("a");
        Task ta = running(a, null, null, 0);
        assertThatThrownBy(() -> delegation.delegateTask(ta.getId(), a.getId(), "x", "y", null)).isInstanceOf(UnprocessableException.class);
    }

    @Test void blocksTheFourthLevel() {
        Agent a = agent("a"), b = agent("b"), c = agent("c"), d = agent("d"), e = agent("e");
        Task t = running(a, null, null, 0);
        for (Agent next : new Agent[] {b, c, d}) {       // depth 1, 2, 3
            Task child = delegation.delegateTask(t.getId(), next.getId(), "x", "y", null);
            taskService.transition(child.getId(), TaskStatus.RUNNING, "test");
            t = tasks.findById(child.getId()).orElseThrow();
        }
        assertThat(t.getDepth()).isEqualTo(3);
        Task deepest = t;
        assertThatThrownBy(() -> delegation.delegateTask(deepest.getId(), e.getId(), "x", "y", null))
                .isInstanceOf(UnprocessableException.class).hasMessageContaining("độ sâu");
    }

    @Test void blocksTheTwentyFirstTaskOfAGoal() {
        Agent a = agent("a"), b = agent("b");
        Goal g = goals.create("g", null, null, null);
        goals.move(g.getId(), GoalStatus.PLANNING);
        goals.move(g.getId(), GoalStatus.RUNNING);
        Task parent = running(a, g.getId(), null, 0);
        for (int i = 0; i < 19; i++) taskService.create(new NewTask(g.getId(), null, a.getId(), "ORCHESTRATOR", "f" + i, "i", Priority.NORMAL, 0));
        assertThat(tasks.countByGoalId(g.getId())).isEqualTo(20);
        assertThatThrownBy(() -> delegation.delegateTask(parent.getId(), b.getId(), "x", "y", null)).isInstanceOf(UnprocessableException.class);
    }

    @Test void refusesOfflineTargetAndNonRunningSource() {
        Agent a = agent("a"), b = agent("b");
        Task ta = running(a, null, null, 0);
        jdbc.update("UPDATE agent SET status='OFFLINE' WHERE id=?", b.getId());
        assertThatThrownBy(() -> delegation.delegateTask(ta.getId(), b.getId(), "x", "y", null)).isInstanceOf(UnprocessableException.class);
        Task queued = taskService.settle(taskService.create(new NewTask(null, null, a.getId(), "HUMAN", "q", "i", Priority.NORMAL, 0)).getId());
        assertThatThrownBy(() -> delegation.delegateTask(queued.getId(), agent("c").getId(), "x", "y", null)).isInstanceOf(IllegalStateException.class);
    }

    @Test void askBlocksTheParentOnTheChild() {
        Agent a = agent("a"), b = agent("b");
        Task parent = running(a, null, null, 0);
        Task child = delegation.askAgent(parent.getId(), b.getId(), "1+1?");
        assertThat(tasks.findById(parent.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(deps.findByTaskId(parent.getId())).extracting(TaskDependency::getDependsOnTaskId).containsExactly(child.getId());
        assertThat(child.getInput()).isEqualTo("1+1?");
        assertThat(eventTypes()).contains("task.handoff");
    }

    @Test void askAlsoRespectsLoopProtection() {
        Agent a = agent("a");
        Task ta = running(a, null, null, 0);
        assertThatThrownBy(() -> delegation.askAgent(ta.getId(), a.getId(), "?")).isInstanceOf(UnprocessableException.class);
        assertThat(tasks.findById(ta.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.RUNNING);
    }
}
