package com.agentoffice.controller.approval;

import jakarta.servlet.http.HttpServletResponse;

import com.agentoffice.controller.support.Paging;

import com.agentoffice.domain.approval.ApprovalKind;
import com.agentoffice.domain.approval.ApprovalStatus;
import com.agentoffice.domain.approval.RiskLevel;
import com.agentoffice.dto.approval.ApprovalDto;
import com.agentoffice.entity.approval.Approval;
import com.agentoffice.mapper.approval.ApprovalMapper;
import com.agentoffice.repository.approval.ApprovalRepository;
import com.agentoffice.service.approval.ApprovalGate;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.security.SensitiveAction;
import com.agentoffice.service.task.OrchestratorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Định tuyến theo approval.kind: PLAN -> OrchestratorService, TOOL_CALL -> ApprovalGate. Không có @Transactional ở đây. */
@RestController
@RequestMapping("/api/approvals")
public class ApprovalController {
    public record ApproveBody(@Size(max = 1000) String note) {}
    public record RejectBody(@NotBlank @Size(max = 1000) String reason) {}

    private final ApprovalRepository approvals;
    private final ApprovalGate gate;
    private final OrchestratorService orchestrator;

    private final ApprovalMapper mapper;

    public ApprovalController(ApprovalRepository approvals, ApprovalGate gate, OrchestratorService orchestrator, ApprovalMapper mapper) {
        this.mapper = mapper;
        this.approvals = approvals;
        this.gate = gate;
        this.orchestrator = orchestrator;
    }

    @GetMapping
    public List<ApprovalDto> list(@RequestParam(defaultValue = "PENDING") ApprovalStatus status, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size, HttpServletResponse res) {
        return Paging.slice(approvals.findByStatusOrderByCreatedAtAsc(status).stream().map(mapper::toDto).toList(), page, size, res);
    }

    @PostMapping("/{id}/approve")
    @SensitiveAction("approval.approve")
    public ApprovalDto approve(@PathVariable UUID id, @Valid @RequestBody(required = false) ApproveBody body) {
        Approval a = find(id);
        String note = body == null ? null : body.note();
        if (a.getKind() == ApprovalKind.PLAN) orchestrator.approvePlan(a.getGoalId(), "owner", note);
        else gate.approve(id, "owner", note);
        return mapper.toDto(find(id));
    }

    @PostMapping("/{id}/reject")
    @SensitiveAction("approval.reject")
    public ApprovalDto reject(@PathVariable UUID id, @Valid @RequestBody RejectBody body) {
        Approval a = find(id);
        if (a.getKind() == ApprovalKind.PLAN) {
            if (orchestrator.rejectPlan(a.getGoalId(), "owner", body.reason()).replan())
                orchestrator.planAsync(a.getGoalId(), List.of(body.reason()));
        } else {
            gate.reject(id, "owner", body.reason());
        }
        return mapper.toDto(find(id));
    }

    private Approval find(UUID id) { return approvals.findById(id).orElseThrow(() -> new NotFoundException("approval", id)); }
}
