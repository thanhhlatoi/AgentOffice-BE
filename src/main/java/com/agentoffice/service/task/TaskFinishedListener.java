package com.agentoffice.service.task;

import com.agentoffice.domain.task.TaskFinished;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.entity.task.Task;
import com.agentoffice.entity.task.TaskDependency;
import com.agentoffice.repository.task.TaskDependencyRepository;
import com.agentoffice.repository.task.TaskRepository;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Chạy ĐỒNG BỘ trong transaction đã đổi trạng thái task. Thứ tự:
 *  1. chuyển câu trả lời của ask_agent vào input task cha (nếu có),
 *  2. gọi TaskScheduler.unblockDependents (lõi của bạn: mở khoá / lan FAILED).
 * (Bước tổng hợp goal ở OrchestratorService chạy sau, @Order(2).)
 */
@Component
public class TaskFinishedListener {
    private final TaskRepository tasks;
    private final TaskDependencyRepository deps;
    private final TaskScheduler scheduler;

    public TaskFinishedListener(TaskRepository tasks, TaskDependencyRepository deps, TaskScheduler scheduler) {
        this.tasks = tasks;
        this.deps = deps;
        this.scheduler = scheduler;
    }

    @EventListener
    @Order(1)
    public void onFinished(TaskFinished e) {
        Task finished = tasks.findById(e.taskId()).orElseThrow();
        if (e.status() == TaskStatus.SUCCEEDED) relayAnswerToParent(finished);
        scheduler.unblockDependents(finished);
    }

    /** ask_agent: task con trả lời xong => đính câu trả lời vào input của task cha đang BLOCKED chờ nó. */
    private void relayAnswerToParent(Task child) {
        if (child.getParentTaskId() == null) return;
        List<TaskDependency> dependents = deps.findByDependsOnTaskId(child.getId());
        for (TaskDependency d : dependents) {
            if (!d.getTaskId().equals(child.getParentTaskId())) continue;
            tasks.findById(d.getTaskId()).filter(p -> p.getStatus() == TaskStatus.BLOCKED).ifPresent(p ->
                    p.setInput((p.getInput() == null ? "" : p.getInput())
                            + "\n\n[Trả lời từ agent " + child.getAssigneeAgentId() + "]\n" + child.getResult()));
        }
    }
}
