package com.agentoffice.service.approval;

import com.agentoffice.domain.approval.CanonicalJson;
import com.agentoffice.domain.approval.RiskLevel;
import com.agentoffice.domain.tool.Tool;
import com.agentoffice.service.tool.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ĐIỂM VÀO DUY NHẤT để agent gọi tool (cổng nằm trong backend, không dựa vào việc AI "nhớ" hỏi).
 * Quyết định theo ApprovalPolicy: cần duyệt => ApprovalGate.request (task -> WAITING_APPROVAL); không cần => chạy ngay.
 * Chưa gắn ToolExecutor (grant/scope/taint) — để dành cho BE-10.
 */
@Service
public class ToolGateway {
    public enum Status { EXECUTED, PENDING_APPROVAL }
    public record Invocation(Status status, String output, UUID approvalId) {}

    private final ToolRegistry registry;
    private final ApprovalPolicyService policies;
    private final ApprovalGate gate;
    private final ObjectMapper mapper;

    public ToolGateway(ToolRegistry registry, ApprovalPolicyService policies, ApprovalGate gate, ObjectMapper mapper) {
        this.registry = registry;
        this.policies = policies;
        this.gate = gate;
        this.mapper = mapper;
    }

    @Transactional
    public Invocation invoke(UUID taskId, UUID agentId, String toolName, String argsJson) {
        Tool tool = registry.require(toolName);                    // tool lạ => NotFound; không có đường vòng
        RiskLevel risk = tool.spec().riskLevel();                  // rủi ro lấy từ ToolSpec, KHÔNG tin agent khai
        if (policies.requiresApproval(toolName, risk)) {
            UUID id = gate.request(taskId, agentId, toolName, risk, argsJson);
            return new Invocation(Status.PENDING_APPROVAL, null, id);
        }
        try {
            JsonNode args = mapper.readTree(argsJson);
            String key = CanonicalJson.idempotencyKey(taskId, toolName, CanonicalJson.canonicalize(argsJson));
            return new Invocation(Status.EXECUTED, tool.execute(args, key), null);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("args không phải JSON hợp lệ", e);
        }
    }
}
