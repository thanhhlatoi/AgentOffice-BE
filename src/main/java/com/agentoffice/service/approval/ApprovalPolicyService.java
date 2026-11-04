package com.agentoffice.service.approval;

import com.agentoffice.domain.approval.RiskLevel;
import com.agentoffice.entity.approval.ApprovalPolicy;
import com.agentoffice.repository.approval.ApprovalPolicyRepository;
import com.agentoffice.service.audit.PermissionAuditLog;
import com.agentoffice.exception.UnprocessableException;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Chính sách duyệt. MONEY/DESTRUCTIVE luôn bắt duyệt: service ném 422, DB có CHECK chặn thêm một lớp. */
@Service
public class ApprovalPolicyService {
    private final ApprovalPolicyRepository repo;
    private final PermissionAuditLog audit;

    public ApprovalPolicyService(ApprovalPolicyRepository repo, PermissionAuditLog audit) {
        this.repo = repo;
        this.audit = audit;
    }

    static boolean alwaysAsk(RiskLevel r) { return r == RiskLevel.MONEY || r == RiskLevel.DESTRUCTIVE; }

    /** Policy theo tool (nếu có) thắng policy theo mức rủi ro; mức rủi ro không có dòng => mặc định theo bảng của tài liệu. */
    @Transactional(readOnly = true)
    public boolean requiresApproval(String toolName, RiskLevel risk) {
        if (alwaysAsk(risk)) return true;                       // phòng thủ chiều sâu, bất kể dữ liệu DB
        return repo.findByToolNameAndRiskLevel(toolName, risk).map(ApprovalPolicy::isRequiresApproval)
                .or(() -> repo.findByToolNameIsNullAndRiskLevel(risk).map(ApprovalPolicy::isRequiresApproval))
                .orElse(risk == RiskLevel.EXTERNAL);
    }

    @Transactional(readOnly = true)
    public List<ApprovalPolicy> list() { return repo.findAll(Sort.by("riskLevel", "toolName")); }

    @Transactional
    public ApprovalPolicy put(String toolName, RiskLevel risk, boolean requiresApproval, String actor) {
        if (!requiresApproval && alwaysAsk(risk))
            throw new UnprocessableException("Không được tắt duyệt cho mức rủi ro " + risk);
        ApprovalPolicy p = (toolName == null ? repo.findByToolNameIsNullAndRiskLevel(risk) : repo.findByToolNameAndRiskLevel(toolName, risk))
                .orElseGet(() -> new ApprovalPolicy(toolName, risk, requiresApproval));
        p.setRequiresApproval(requiresApproval);
        ApprovalPolicy saved = repo.saveAndFlush(p);
        java.util.HashMap<String, Object> d = new java.util.HashMap<>();
        d.put("toolName", toolName);
        d.put("riskLevel", risk);
        d.put("requiresApproval", requiresApproval);
        audit.record(actor, "APPROVAL_POLICY_CHANGED", d);
        return saved;
    }
}
