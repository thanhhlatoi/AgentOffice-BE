package com.agentoffice.domain.task;

import java.util.UUID;

/** Lệnh tạo task. goalId/parentTaskId có thể null (task lẻ do chủ giao thẳng cho agent). */
public record NewTask(UUID goalId, UUID parentTaskId, UUID assigneeAgentId, String createdBy,
                      String title, String input, Priority priority, int depth) {}
