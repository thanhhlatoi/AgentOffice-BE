package com.agentoffice.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** agentoffice.scheduler.* */
@Validated
@ConfigurationProperties("agentoffice.scheduler")
public record SchedulerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1000") @Min(10) long tickMs,
        @DefaultValue("4") @Min(1) int maxConcurrentTasks,
        @DefaultValue({"5", "30", "120"}) @NotEmpty List<@PositiveOrZero Long> backoffSeconds) {}
