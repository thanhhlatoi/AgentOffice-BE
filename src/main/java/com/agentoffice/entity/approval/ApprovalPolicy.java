package com.agentoffice.entity.approval;

import lombok.Getter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import com.agentoffice.domain.approval.RiskLevel;
import jakarta.persistence.*;
import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "approval_policy")
public class ApprovalPolicy {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String toolName;          // null = áp dụng cho cả mức rủi ro

    @Enumerated(EnumType.STRING)
    private RiskLevel riskLevel;

    private boolean requiresApproval;
    private String mode = "ASK";      // cột cũ (V1), không còn dùng; giữ để không mất dữ liệu

    public ApprovalPolicy(String toolName, RiskLevel riskLevel, boolean requiresApproval) {
        this.toolName = toolName;
        this.riskLevel = riskLevel;
        this.requiresApproval = requiresApproval;
        this.mode = requiresApproval ? "ASK" : "AUTO";
    }

    public void setRequiresApproval(boolean v) { this.requiresApproval = v; this.mode = v ? "ASK" : "AUTO"; }
}
