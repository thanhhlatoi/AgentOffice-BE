package com.agentoffice.service.approval;

import com.agentoffice.config.ApprovalProperties;
import com.agentoffice.domain.approval.ApprovalKind;
import com.agentoffice.domain.approval.ApprovalStatus;
import com.agentoffice.domain.approval.CanonicalJson;
import com.agentoffice.domain.approval.RiskLevel;
import com.agentoffice.domain.event.EventTypes;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.domain.tool.Tool;
import com.agentoffice.entity.approval.Approval;
import com.agentoffice.entity.approval.ApprovalAudit;
import com.agentoffice.entity.task.Task;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.repository.approval.ApprovalAuditRepository;
import com.agentoffice.repository.approval.ApprovalRepository;
import com.agentoffice.service.tool.ToolRegistry;
import com.agentoffice.service.event.EventPublisher;
import com.agentoffice.service.task.TaskService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cổng duyệt lời gọi tool (kind=TOOL_CALL). Duyệt kế hoạch (kind=PLAN) do OrchestratorService lo.
 *
 * - request: idempotent theo (task, tool, args chuẩn hoá) — khoá tư vấn chống hai request đua nhau; task RUNNING -> WAITING_APPROVAL.
 * - approve: BA PHA để tool chạy tối đa MỘT lần, kể cả khi hai tab bấm cùng lúc hoặc máy chết giữa chừng:
 *     1) transaction: kiểm tra (task còn chờ, chưa huỷ), CHIẾM approval bằng UPDATE ... WHERE status='PENDING' AND chưa hết hạn, ghi audit APPROVED, COMMIT;
 *     2) KHÔNG transaction: chạy tool với ĐÚNG argsSnapshot đã lưu (không dựng lại args);
 *     3) transaction: ghi executedAt + audit EXECUTED (hoặc EXECUTION_FAILED), đưa task về RUNNING (hoặc FAILED), phát approval.resolved.
 *   Máy chết giữa pha 1 và 3 => approval ở APPROVED mà executedAt rỗng: lộ ra để người xử lý, và tool KHÔNG bị chạy lại (an toàn hơn chạy hai lần
 *   cho email/chuyển tiền). Nếu approve() được gọi bên trong một transaction ngoài thì ba pha dồn thành một (mất tính chất này).
 * - reject / expireOverdue: PENDING -> REJECTED/EXPIRED rồi xử lý task theo props.onReject() (FAIL_TASK hoặc RETURN_TO_AGENT).
 *   expireOverdue dùng FOR UPDATE SKIP LOCKED nên hai instance chạy job cùng lúc không xử lý trùng.
 * Mọi thay đổi approval + task + event nằm trong cùng transaction của pha tương ứng (EventPublisher là MANDATORY).
 * Kết quả (output) của tool chưa được lưu vào đâu: runner đọc trạng thái task; sẽ làm cùng hệ tool ở BE-10.
 */
@Service
public class ApprovalGate {
    private final ApprovalRepository approvals;
    private final ApprovalAuditRepository audits;
    private final ToolRegistry registry;
    private final TaskService taskService;
    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final ApprovalProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ApprovalGate(ApprovalRepository approvals, ApprovalAuditRepository audits, ToolRegistry registry,
                        TaskService taskService, EventPublisher events, ObjectMapper mapper,
                        ApprovalProperties props, Clock clock, PlatformTransactionManager txManager) {
        this.approvals = approvals;
        this.audits = audits;
        this.registry = registry;
        this.taskService = taskService;
        this.events = events;
        this.mapper = mapper;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    private record Claimed(UUID approvalId, UUID taskId, String toolName, JsonNode args, String idempotencyKey) {}

    @Transactional
    public UUID request(UUID taskId, UUID agentId, String toolName, RiskLevel risk, String argsJson) {
        Tool tool = registry.require(toolName);
        String canonical = CanonicalJson.canonicalize(argsJson);
        String key = CanonicalJson.idempotencyKey(taskId, toolName, canonical);
        approvals.lockKey(key);                                        // hai request cùng key xếp hàng; kẻ sau thấy approval của kẻ trước
        Optional<Approval> existing = approvals.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            Approval old = existing.get();
            if (old.getStatus() == ApprovalStatus.REJECTED || old.getStatus() == ApprovalStatus.EXPIRED)
                throw new IllegalStateException("Lời gọi tool '" + toolName + "' này đã bị " + old.getStatus() + " ("
                        + (old.getReason() == null ? "không rõ lý do" : old.getReason()) + "); hãy đổi nội dung nếu muốn xin lại");
            return old.getId();
        }

        Task task = taskService.get(taskId);
        if (task.getStatus() != TaskStatus.RUNNING && task.getStatus() != TaskStatus.WAITING_APPROVAL)
            throw new IllegalStateException("Task " + taskId + " đang " + task.getStatus() + ", không xin duyệt được");

        JsonNode args = parse(canonical);
        Approval a = approvals.saveAndFlush(Approval.toolCall(taskId, agentId, toolName, risk, canonical, tool.preview(args), key,
                clock.instant().plus(props.ttl())));
        audit(a.getId(), "agent:" + agentId, "REQUESTED", Map.of("toolName", toolName, "risk", risk, "taskId", taskId));
        if (task.getStatus() == TaskStatus.RUNNING)                    // dispatcher có thể đã chuyển rồi: đừng chuyển lần hai
            taskService.transition(taskId, TaskStatus.WAITING_APPROVAL, "tool '" + toolName + "' needs approval");

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("approvalId", a.getId());
        p.put("kind", ApprovalKind.TOOL_CALL);
        p.put("taskId", taskId);
        p.put("agentId", agentId);
        p.put("toolName", toolName);
        p.put("riskLevel", risk);
        p.put("preview", a.getPreview());
        p.put("expiresAt", a.getExpiresAt().toString());
        events.publish(EventTypes.APPROVAL_REQUESTED, a.getId(), p);
        return a.getId();
    }

    /** Không @Transactional: tự chia ba pha (xem Javadoc lớp). IllegalStateException => HTTP 409. */
    public void approve(UUID approvalId, String decidedBy, String note) {
        Claimed c = tx.execute(s -> claimForExecution(approvalId, decidedBy, note));
        String output = null;
        RuntimeException failure = null;
        try {
            output = registry.require(c.toolName()).execute(c.args(), c.idempotencyKey());
        } catch (RuntimeException e) {
            failure = e;
        }
        final String out = output;
        final RuntimeException err = failure;
        tx.executeWithoutResult(s -> finishExecution(c, out, err));
    }

    private Claimed claimForExecution(UUID approvalId, String decidedBy, String note) {
        Approval a = approvals.findById(approvalId).orElseThrow(() -> new NotFoundException("approval", approvalId));
        if (a.getKind() != ApprovalKind.TOOL_CALL) throw new IllegalStateException("Approval " + approvalId + " không phải lời gọi tool");
        registry.require(a.getToolName());                              // tool biến mất => lỗi TRƯỚC khi chiếm approval
        Task task = taskService.get(a.getTaskId());
        if (task.getStatus() != TaskStatus.WAITING_APPROVAL || task.isCancelRequested())
            throw new IllegalStateException("Task " + a.getTaskId() + " không còn chờ duyệt (" + task.getStatus() + "): không chạy tool");
        JsonNode args = parse(a.getArgsSnapshot());
        Instant now = clock.instant();
        if (approvals.claim(approvalId, ApprovalStatus.APPROVED, decidedBy, now, note) == 0) {
            Approval cur = approvals.findById(approvalId).orElseThrow();
            if (cur.getStatus() != ApprovalStatus.PENDING)
                throw new IllegalStateException("Approval đã được xử lý (" + cur.getStatus() + ")");
            throw new IllegalStateException("Approval đã quá hạn lúc " + cur.getExpiresAt());
        }
        audit(approvalId, decidedBy, "APPROVED", note == null ? Map.of() : Map.of("note", note));
        return new Claimed(approvalId, a.getTaskId(), a.getToolName(), args, a.getIdempotencyKey());
    }

    private void finishExecution(Claimed c, String output, RuntimeException failure) {
        Instant now = clock.instant();
        boolean waiting = taskService.get(c.taskId()).getStatus() == TaskStatus.WAITING_APPROVAL;   // có thể đã bị huỷ trong lúc tool chạy
        if (failure == null) {
            approvals.markExecuted(c.approvalId(), now);
            audit(c.approvalId(), "system", "EXECUTED", Map.of("outputChars", output == null ? 0 : output.length()));
            if (waiting) taskService.transition(c.taskId(), TaskStatus.RUNNING, "approved and executed: " + c.toolName());
        } else {
            audit(c.approvalId(), "system", "EXECUTION_FAILED", Map.of("error", abbreviate(failure.getClass().getSimpleName() + ": " + failure.getMessage())));
            // Tool đã được duyệt nhưng lỗi: KHÔNG chạy lại tự động (có thể đã gây tác động một phần); đánh dấu task FAILED để người xem xét.
            if (waiting) taskService.fail(c.taskId(), "Tool '" + c.toolName() + "' lỗi sau khi được duyệt: " + abbreviate(String.valueOf(failure.getMessage())));
        }
        resolvedEvent(c.approvalId(), c.taskId(), c.toolName(), ApprovalStatus.APPROVED, failure == null);
    }

    @Transactional
    public void reject(UUID approvalId, String decidedBy, String reason) {
        Approval a = approvals.findById(approvalId).orElseThrow(() -> new NotFoundException("approval", approvalId));
        if (a.getKind() != ApprovalKind.TOOL_CALL) throw new IllegalStateException("Approval " + approvalId + " không phải lời gọi tool");
        if (approvals.close(approvalId, ApprovalStatus.REJECTED, decidedBy, clock.instant(), reason) == 0)
            throw new IllegalStateException("Approval đã được xử lý");
        audit(approvalId, decidedBy, "REJECTED", reason == null ? Map.of() : Map.of("reason", reason));
        endTask(a.getTaskId(), "Chủ từ chối: " + (reason == null ? "không nêu lý do" : reason));
        resolvedEvent(approvalId, a.getTaskId(), a.getToolName(), ApprovalStatus.REJECTED, false);
    }

    @Transactional
    public int expireOverdue() {
        Instant now = clock.instant();
        List<Approval> overdue = approvals.lockOverdueToolCalls(now);
        for (Approval a : overdue) {
            a.setStatus(ApprovalStatus.EXPIRED);
            a.setDecidedBy("system");
            a.setDecidedAt(now);
            a.setReason("Hết hạn duyệt");
            approvals.save(a);
            audit(a.getId(), "system", "EXPIRED", Map.of("expiresAt", a.getExpiresAt().toString()));
            endTask(a.getTaskId(), "Lời xin duyệt tool '" + a.getToolName() + "' đã hết hạn");
            resolvedEvent(a.getId(), a.getTaskId(), a.getToolName(), ApprovalStatus.EXPIRED, false);
        }
        return overdue.size();
    }

    /** Từ chối/hết hạn: xử lý task theo props.onReject(). Task đã bị huỷ/đổi trạng thái thì để yên. */
    private void endTask(UUID taskId, String why) {
        if (taskService.get(taskId).getStatus() != TaskStatus.WAITING_APPROVAL) return;
        switch (props.onReject()) {
            case FAIL_TASK -> taskService.fail(taskId, why);
            case RETURN_TO_AGENT -> taskService.transition(taskId, TaskStatus.RUNNING, why);   // lý do nằm ở approval.reason để runner đọc
        }
    }

    private void resolvedEvent(UUID approvalId, UUID taskId, String toolName, ApprovalStatus status, boolean executed) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("approvalId", approvalId);
        p.put("kind", ApprovalKind.TOOL_CALL);
        p.put("taskId", taskId);
        p.put("toolName", toolName);
        p.put("status", status);
        p.put("executed", executed);
        events.publish(EventTypes.APPROVAL_RESOLVED, approvalId, p);
    }

    private void audit(UUID approvalId, String actor, String action, Map<String, Object> detail) {
        audits.save(new ApprovalAudit(approvalId, actor, action, json(detail)));
    }

    private JsonNode parse(String json) {
        try { return mapper.readTree(json); }
        catch (JsonProcessingException e) { throw new IllegalStateException("args đã lưu không phải JSON hợp lệ", e); }
    }

    private String json(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }

    private static String abbreviate(String s) {
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 300 ? one : one.substring(0, 300) + "…";
    }
}
