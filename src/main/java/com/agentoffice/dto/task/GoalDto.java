package com.agentoffice.dto.task;

import com.agentoffice.domain.task.GoalStatus;
import java.time.Instant;
import java.util.UUID;

public record GoalDto(UUID id, String title, String description, GoalStatus status, UUID departmentId,
                      Long tokenBudget, long tokensUsed, Instant createdAt, Instant finishedAt,
                      String result, String error) {}
