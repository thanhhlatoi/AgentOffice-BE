package com.agentoffice.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** agentoffice.limits.* — giới hạn đã chốt: độ sâu giao việc 3, 20 task/goal. */
@Validated
@ConfigurationProperties("agentoffice.limits")
public record LimitsProperties(@DefaultValue("3") @Min(1) int maxTaskDepth, @DefaultValue("20") @Min(1) int maxTasksPerGoal) {}
