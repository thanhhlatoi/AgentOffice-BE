package com.agentoffice.service.approval;

import com.agentoffice.service.task.OrchestratorService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Mỗi phút quét approval quá hạn: lời gọi tool (ApprovalGate) và kế hoạch goal (OrchestratorService). */
@Component
public class ApprovalExpiryJob {
    private final ApprovalGate gate;
    private final OrchestratorService orchestrator;

    public ApprovalExpiryJob(ApprovalGate gate, OrchestratorService orchestrator) {
        this.gate = gate;
        this.orchestrator = orchestrator;
    }

    @Scheduled(fixedDelayString = "${agentoffice.approval.expiry-scan-ms:60000}")
    public void scan() {
        orchestrator.expirePlans();
        gate.expireOverdue();
    }
}
