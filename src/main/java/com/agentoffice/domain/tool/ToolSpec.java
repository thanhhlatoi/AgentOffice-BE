package com.agentoffice.domain.tool;

import com.agentoffice.domain.approval.RiskLevel;
public record ToolSpec(String name, RiskLevel riskLevel) {}
