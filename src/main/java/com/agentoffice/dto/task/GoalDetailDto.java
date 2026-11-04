package com.agentoffice.dto.task;

import java.util.List;

/** Goal + cây task + tiến độ (% task SUCCEEDED trên tổng task chưa bị huỷ). */
public record GoalDetailDto(GoalDto goal, int progressPercent, List<TaskNode> tasks, String plan) {
    public record TaskNode(TaskDto task, List<TaskNode> children) {}
}
