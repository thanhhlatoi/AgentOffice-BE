package com.agentoffice.service.task;

import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.entity.task.Task;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.exception.UnprocessableException;
import com.agentoffice.service.event.EventPublisher;
import com.agentoffice.domain.event.EventTypes;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Công cụ nội bộ delegate_task / ask_agent (chưa qua ToolExecutor — gắn ở BE-10).
 * Bảo vệ: độ sâu <= 3 và tối đa task/goal (TaskService.create), chặn vòng A->B->A (chuỗi người giao),
 * không giao cho agent OFFLINE (TaskService.create).
 */
@Service
public class DelegationService {
    private final TaskService taskService;
    private final TaskRepository tasks;
    private final EventPublisher events;

    public DelegationService(TaskService taskService, TaskRepository tasks, EventPublisher events) {
        this.taskService = taskService;
        this.tasks = tasks;
        this.events = events;
    }

    /** Giao task con, KHÔNG chờ. */
    @Transactional
    public Task delegateTask(UUID fromTaskId, UUID toAgentId, String title, String input, Priority priority) {
        Task from = runningTask(fromTaskId);
        Task child = newChild(from, toAgentId, title, input, priority);
        taskService.settle(child.getId());
        handoff(from, child, title);
        return tasks.findById(child.getId()).orElseThrow();
    }

    /** Hỏi agent khác: tạo task con, đặt task hiện tại BLOCKED tới khi có trả lời (câu trả lời được đính vào input của nó). */
    @Transactional
    public Task askAgent(UUID fromTaskId, UUID toAgentId, String question) {
        Task from = runningTask(fromTaskId);
        Task child = newChild(from, toAgentId, "Hỏi: " + truncate(question, 200), question, from.getPriority());
        taskService.addDependency(from.getId(), child.getId());
        taskService.settle(child.getId());
        taskService.transition(from.getId(), TaskStatus.BLOCKED, "đang chờ trả lời từ agent " + toAgentId);
        handoff(from, child, question);
        return tasks.findById(child.getId()).orElseThrow();
    }

    private Task runningTask(UUID id) {
        Task t = taskService.get(id);
        if (t.getStatus() != TaskStatus.RUNNING) throw new IllegalStateException("Chỉ task đang RUNNING mới giao việc được, hiện là " + t.getStatus());
        return t;
    }

    private Task newChild(Task from, UUID toAgentId, String title, String input, Priority priority) {
        rejectLoop(from, toAgentId);
        return taskService.create(new NewTask(from.getGoalId(), from.getId(), toAgentId,
                String.valueOf(from.getAssigneeAgentId()), title, input, priority, from.getDepth() + 1));
    }

    /** Duyệt ngược chuỗi cha: nếu agent đích đã là người thực hiện ở bất kỳ mắt xích nào (kể cả chính người giao) => vòng lặp. */
    private void rejectLoop(Task from, UUID toAgentId) {
        Set<UUID> seen = new HashSet<>();
        Task cur = from;
        while (cur != null && seen.add(cur.getId())) {
            if (toAgentId.equals(cur.getAssigneeAgentId()))
                throw new UnprocessableException("Vòng lặp giao việc: agent " + toAgentId + " đã nằm trong chuỗi giao việc");
            cur = cur.getParentTaskId() == null ? null : tasks.findById(cur.getParentTaskId()).orElse(null);
        }
    }

    private void handoff(Task from, Task child, String message) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("fromAgentId", from.getAssigneeAgentId());
        p.put("toAgentId", child.getAssigneeAgentId());
        p.put("taskId", child.getId());
        p.put("message", truncate(message, 300));
        events.publish(EventTypes.TASK_HANDOFF, child.getId(), p);
    }

    private static String truncate(String s, int n) { return s.length() <= n ? s : s.substring(0, n); }
}
