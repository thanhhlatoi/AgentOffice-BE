package com.agentoffice.controller.approval;

import com.agentoffice.domain.approval.RiskLevel;
import com.agentoffice.dto.approval.PolicyDto;
import com.agentoffice.mapper.approval.ApprovalMapper;
import com.agentoffice.service.approval.ApprovalPolicyService;
import com.agentoffice.security.SensitiveAction;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/approval-policies")
public class ApprovalPolicyController {
    public record PutPolicy(@Size(max = 100) String toolName, @NotNull RiskLevel riskLevel, @NotNull Boolean requiresApproval) {}

    private final ApprovalPolicyService policies;

    private final ApprovalMapper mapper;

    public ApprovalPolicyController(ApprovalPolicyService policies, ApprovalMapper mapper) {
        this.policies = policies;
        this.mapper = mapper;
    }

    @GetMapping
    public List<PolicyDto> list() { return mapper.toPolicyDtos(policies.list()); }

    @PutMapping
    @SensitiveAction("approval-policy.update")
    public PolicyDto put(@Valid @RequestBody PutPolicy r) {
        return mapper.toDto(policies.put(r.toolName(), r.riskLevel(), r.requiresApproval(), "owner"));
    }
}
