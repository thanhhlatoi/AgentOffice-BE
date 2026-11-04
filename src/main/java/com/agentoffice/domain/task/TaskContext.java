package com.agentoffice.domain.task;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Mọi thứ AgentRunner cần để chạy 1 task. cancelRequested đọc lại từ DB mỗi lần gọi (huỷ hợp tác). */
public record TaskContext(UUID taskId, UUID agentId, String agentModelProfile, UUID goalId, String title,
                          String input, int attempts, int depth, BooleanSupplier cancelRequested) {}
