package com.agentoffice.dto.approval;

import com.agentoffice.domain.approval.ApprovalKind;
import com.agentoffice.domain.approval.ApprovalStatus;
import com.agentoffice.domain.approval.RiskLevel;
import java.time.Instant;
import java.util.UUID;

/** Không có argsSnapshot/idempotencyKey: người duyệt đọc `preview`. */
public record ApprovalDto(UUID id, ApprovalKind kind, UUID taskId, UUID goalId, UUID agentId, String toolName,
                          RiskLevel riskLevel, String preview, ApprovalStatus status, String decidedBy,
                          Instant decidedAt, String reason, Instant expiresAt, Instant createdAt) {}
