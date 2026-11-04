package com.agentoffice.entity.approval;

import lombok.Getter;
import lombok.Setter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import com.agentoffice.domain.approval.ApprovalKind;
import com.agentoffice.domain.approval.ApprovalStatus;
import com.agentoffice.domain.approval.RiskLevel;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "approval")
public class Approval {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    private ApprovalKind kind;

    private UUID taskId;
    private UUID agentId;      // null với kind=PLAN
    private UUID goalId;       // chỉ kind=PLAN
    private String toolName;

    @Enumerated(EnumType.STRING)
    private RiskLevel riskLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String argsSnapshot;

    private String preview;
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Setter
    private ApprovalStatus status = ApprovalStatus.PENDING;

    @Setter
    private String decidedBy;
    @Setter
    private Instant decidedAt;
    @Setter
    private String reason;
    private Instant expiresAt;
    private Instant createdAt = Instant.now();
    @Setter
    private Instant executedAt;

    public static Approval plan(UUID goalId, String planJson, String preview, String idempotencyKey, Instant expiresAt) {
        Approval a = new Approval();
        a.kind = ApprovalKind.PLAN;
        a.goalId = goalId;
        a.argsSnapshot = planJson;
        a.preview = preview;
        a.idempotencyKey = idempotencyKey;
        a.expiresAt = expiresAt;
        return a;
    }

    public static Approval toolCall(UUID taskId, UUID agentId, String toolName, RiskLevel risk, String argsSnapshot,
                                    String preview, String idempotencyKey, Instant expiresAt) {
        Approval a = new Approval();
        a.kind = ApprovalKind.TOOL_CALL;
        a.taskId = taskId;
        a.agentId = agentId;
        a.toolName = toolName;
        a.riskLevel = risk;
        a.argsSnapshot = argsSnapshot;
        a.preview = preview;
        a.idempotencyKey = idempotencyKey;
        a.expiresAt = expiresAt;
        return a;
    }

}
