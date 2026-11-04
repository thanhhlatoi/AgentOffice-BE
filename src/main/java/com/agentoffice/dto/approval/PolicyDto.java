package com.agentoffice.dto.approval;

import com.agentoffice.domain.approval.RiskLevel;

public record PolicyDto(String toolName, RiskLevel riskLevel, boolean requiresApproval) {}
