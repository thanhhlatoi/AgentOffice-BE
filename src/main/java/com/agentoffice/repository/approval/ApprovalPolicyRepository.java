package com.agentoffice.repository.approval;

import com.agentoffice.domain.approval.RiskLevel;
import com.agentoffice.entity.approval.ApprovalPolicy;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApprovalPolicyRepository extends JpaRepository<ApprovalPolicy, UUID> {
    Optional<ApprovalPolicy> findByToolNameAndRiskLevel(String toolName, RiskLevel riskLevel);
    Optional<ApprovalPolicy> findByToolNameIsNullAndRiskLevel(RiskLevel riskLevel);
    Optional<ApprovalPolicy> findFirstByToolName(String toolName);
}
