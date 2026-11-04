package com.agentoffice.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.entity.task.Task;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.support.PostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** activeDurationSec chỉ đếm thời gian RUNNING; waitDurationSec đếm WAITING_APPROVAL + WAITING_BUDGET; thời gian xếp hàng không đếm. */
class TaskDurationIT extends PostgresIT {
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;

    private static final Instant T0 = Instant.parse("2026-03-10T03:00:00Z");

    @Test void activeAndWaitTimeAreSeparated() {
        clock.set(T0);
        Agent a = agent("a");
        Task t = taskService.create(new NewTask(null, null, a.getId(), "HUMAN", "t", "in", Priority.NORMAL, 0));
        taskService.settle(t.getId());                                            // QUEUED lúc T0
        UUID id = t.getId();
        clock.set(T0.plusSeconds(5));   taskService.transition(id, TaskStatus.RUNNING, "t");            // xếp hàng 5s: không đếm
        clock.set(T0.plusSeconds(15));  taskService.transition(id, TaskStatus.WAITING_APPROVAL, "t");   // chạy 10s
        clock.set(T0.plusSeconds(75));  taskService.transition(id, TaskStatus.RUNNING, "t");            // chờ duyệt 60s
        clock.set(T0.plusSeconds(80));  taskService.transition(id, TaskStatus.WAITING_BUDGET, "t");     // chạy thêm 5s
        clock.set(T0.plusSeconds(180)); taskService.transition(id, TaskStatus.QUEUED, "t");             // chờ ngân sách 100s
        clock.set(T0.plusSeconds(185)); taskService.transition(id, TaskStatus.RUNNING, "t");
        clock.set(T0.plusSeconds(200)); taskService.transition(id, TaskStatus.SUCCEEDED, "t");          // chạy thêm 15s

        Task r = tasks.findById(id).orElseThrow();
        assertThat(r.getActiveDurationSec()).isEqualTo(10 + 5 + 15);
        assertThat(r.getWaitDurationSec()).isEqualTo(60 + 100);
    }
}
