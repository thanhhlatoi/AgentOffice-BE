package com.agentoffice.repository.approval;

import com.agentoffice.entity.approval.ApprovalAudit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApprovalAuditRepository extends JpaRepository<ApprovalAudit, UUID> {
    List<ApprovalAudit> findByApprovalIdOrderByCreatedAtAsc(UUID approvalId);
}
