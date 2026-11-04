package com.agentoffice.domain.task;

import com.agentoffice.domain.task.Priority;
import java.util.List;
import java.util.UUID;

/** Kế hoạch Planner đề xuất: danh sách subtask + phụ thuộc theo key. */
public record Plan(List<SubTask> tasks) {
    public record SubTask(String key, String title, String input, UUID assigneeAgentId,
                          Priority priority, List<String> dependsOnKeys) {
        public SubTask {
            dependsOnKeys = dependsOnKeys == null ? List.of() : dependsOnKeys;
            priority = priority == null ? Priority.NORMAL : priority;
        }
    }

    public Plan {
        tasks = tasks == null ? List.of() : tasks;
    }
}
