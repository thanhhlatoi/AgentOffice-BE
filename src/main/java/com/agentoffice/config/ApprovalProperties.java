package com.agentoffice.config;

import com.agentoffice.domain.approval.RejectMode;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** agentoffice.approval.* */
@Validated
@ConfigurationProperties("agentoffice.approval")
public record ApprovalProperties(
        @DefaultValue("true") boolean requirePlanApproval,
        @DefaultValue("24h") @NotNull Duration ttl,
        @DefaultValue("FAIL_TASK") @NotNull RejectMode onReject) {}
