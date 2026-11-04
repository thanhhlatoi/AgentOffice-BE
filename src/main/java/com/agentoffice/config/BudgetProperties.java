package com.agentoffice.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** agentoffice.budget.* — hạn mức MẪU cho agent mới (1 USD/ngày); chủ đổi bằng API. */
@Validated
@ConfigurationProperties("agentoffice.budget")
public record BudgetProperties(@DefaultValue("1000000") @Min(1) long defaultAgentDayMicroUsd,
                               @DefaultValue("80") @Min(1) int defaultSoftPct,
                               @DefaultValue("60000") @Min(1000) long resumeIntervalMs) {}
