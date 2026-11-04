package com.agentoffice.dto.task;

import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.TaskStatus;
import java.time.Instant;
import java.util.UUID;

public record TaskDto(UUID id, UUID goalId, UUID parentTaskId, UUID assigneeAgentId, String createdBy, String title,
                      String input, TaskStatus status, Priority priority, int depth, int attempts, int maxAttempts,
                      Instant nextRunAt, boolean cancelRequested, String result, String error,
                      Instant startedAt, Instant finishedAt, Instant createdAt,
                      long activeDurationSec, long waitDurationSec,
                      long version, String kanbanRank, UUID projectId, int weight, java.util.List<String> labels, UUID retryOfTaskId) {}
