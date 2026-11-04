package com.agentoffice.service.task;

import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.entity.task.Task;
import com.agentoffice.repository.task.TaskRepository;
import static org.assertj.core.api.Assertions.assertThat;

import com.agentoffice.entity.agent.Agent;
import com.agentoffice.support.CoreIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** ask_agent đầu-cuối với máy trạng thái + scheduler thật (cần RUNNING -> BLOCKED và unblockDependents của bạn). */
class AskAgentFlowIT extends CoreIT {
    @Autowired DelegationService delegation;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;

    private Task running(Agent a, String input) {
        Task t = taskService.create(new NewTask(null, null, a.getId(), "HUMAN", "cha", input, Priority.NORMAL, 0));
        taskService.settle(t.getId());
        taskService.transition(t.getId(), TaskStatus.RUNNING, "test");
        return tasks.findById(t.getId()).orElseThrow();
    }

    @Test void parentBlocksThenResumesWithTheAnswerInItsInput() {
        Agent a = agent("hỏi"), b = agent("đáp");
        Task parent = running(a, "câu hỏi gốc");
        Task child = delegation.askAgent(parent.getId(), b.getId(), "1+1 bằng mấy?");
        assertThat(tasks.findById(parent.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(child.getStatus()).isEqualTo(TaskStatus.QUEUED);

        taskService.transition(child.getId(), TaskStatus.RUNNING, "test");
        taskService.complete(child.getId(), "bằng 2");

        Task p = tasks.findById(parent.getId()).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(TaskStatus.QUEUED);
        assertThat(p.getInput()).contains("câu hỏi gốc").contains("bằng 2");
    }

    @Test void parentFailsIfTheAnswerNeverComes() {
        Agent a = agent("hỏi"), b = agent("đáp");
        Task parent = running(a, "x");
        Task child = delegation.askAgent(parent.getId(), b.getId(), "?");
        taskService.transition(child.getId(), TaskStatus.RUNNING, "test");
        taskService.fail(child.getId(), "không biết");
        assertThat(tasks.findById(parent.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.FAILED);
    }
}
