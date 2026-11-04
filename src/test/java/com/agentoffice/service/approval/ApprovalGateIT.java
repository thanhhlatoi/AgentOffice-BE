package com.agentoffice.service.approval;

import com.agentoffice.domain.approval.ApprovalKind;
import com.agentoffice.domain.approval.ApprovalStatus;
import com.agentoffice.domain.approval.RiskLevel;
import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.domain.tool.Tool;
import com.agentoffice.domain.tool.ToolSpec;
import com.agentoffice.entity.approval.Approval;
import com.agentoffice.entity.approval.ApprovalAudit;
import com.agentoffice.entity.task.Task;
import com.agentoffice.repository.approval.ApprovalAuditRepository;
import com.agentoffice.repository.approval.ApprovalRepository;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.service.task.TaskService;
import com.agentoffice.service.tool.ToolRegistry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.entity.agent.Agent;
import com.agentoffice.support.CoreIT;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Spec cho ApprovalGate. */
class ApprovalGateIT extends CoreIT {
    /** Tool giả: đếm số lần chạy và ghi lại args nhận được. */
    static class CountingTool implements Tool {
        final AtomicInteger runs = new AtomicInteger();
        final List<String> seenArgs = new CopyOnWriteArrayList<>();
        final List<String> seenKeys = new CopyOnWriteArrayList<>();
        final String name; final RiskLevel risk;
        CountingTool(String name, RiskLevel risk) { this.name = name; this.risk = risk; }
        public ToolSpec spec() { return new ToolSpec(name, risk); }
        public String preview(JsonNode args) { return "Gửi email tới " + args.path("to").asText() + ", tiêu đề " + args.path("subject").asText(); }
        public String execute(JsonNode args, String key) { runs.incrementAndGet(); seenArgs.add(args.toString()); seenKeys.add(key); return "sent"; }
    }

    @Autowired ApprovalGate gate;
    @Autowired ApprovalRepository approvals;
    @Autowired ApprovalAuditRepository audits;
    @Autowired ToolRegistry registry;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;

    private static final CountingTool TOOL = new CountingTool("send_email", RiskLevel.EXTERNAL);
    private static boolean registered;

    @BeforeEach
    void setUp() {
        synchronized (ApprovalGateIT.class) { if (!registered) { registry.register(TOOL); registered = true; } }
        TOOL.runs.set(0); TOOL.seenArgs.clear(); TOOL.seenKeys.clear();
    }

    private Task runningTask() {
        Agent a = agent("a-" + UUID.randomUUID());
        Task t = taskService.create(new NewTask(null, null, a.getId(), "HUMAN", "gửi mail", "in", Priority.NORMAL, 0));
        taskService.settle(t.getId());
        taskService.transition(t.getId(), TaskStatus.RUNNING, "test");
        return tasks.findById(t.getId()).orElseThrow();
    }

    private static final String ARGS = "{\"to\":\"x@y.com\",\"subject\":\"Chào\"}";

    private UUID request(Task t) { return gate.request(t.getId(), t.getAssigneeAgentId(), "send_email", RiskLevel.EXTERNAL, ARGS); }

    @Test void requestStoresSnapshotPreviewAndWaitsTheTask() {
        Task t = runningTask();
        UUID id = request(t);
        Approval a = approvals.findById(id).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(a.getKind()).isEqualTo(ApprovalKind.TOOL_CALL);
        assertThat(a.getPreview()).contains("x@y.com").contains("Chào");
        assertThat(a.getArgsSnapshot()).contains("\"to\"").contains("x@y.com");
        assertThat(a.getExpiresAt()).isAfter(Instant.now().plusSeconds(23 * 3600));
        assertThat(tasks.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.WAITING_APPROVAL);
        assertThat(eventTypes()).contains("approval.requested");
        assertThat(audits.findByApprovalIdOrderByCreatedAtAsc(id)).extracting(ApprovalAudit::getAction).containsExactly("REQUESTED");
        assertThat(TOOL.runs.get()).as("chưa duyệt thì tool không được chạy").isZero();
    }

    @Test void sameCallWithReorderedKeysReusesTheApproval() {
        Task t = runningTask();
        UUID first = request(t);
        UUID again = gate.request(t.getId(), t.getAssigneeAgentId(), "send_email", RiskLevel.EXTERNAL,
                "{ \"subject\": \"Chào\",   \"to\": \"x@y.com\" }");
        assertThat(again).isEqualTo(first);
        assertThat(approvals.count()).isEqualTo(1);
    }

    @Test void approveRunsExactlyTheStoredSnapshotAndResumesTheTask() {
        Task t = runningTask();
        UUID id = request(t);
        gate.approve(id, "owner", "ok");
        assertThat(TOOL.runs.get()).isEqualTo(1);
        assertThat(TOOL.seenArgs.get(0)).contains("x@y.com").contains("Chào");
        assertThat(TOOL.seenKeys.get(0)).isEqualTo(approvals.findById(id).orElseThrow().getIdempotencyKey());
        Approval a = approvals.findById(id).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(a.getDecidedBy()).isEqualTo("owner");
        assertThat(a.getExecutedAt()).isNotNull();
        assertThat(tasks.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(eventTypes()).contains("approval.resolved");
        assertThat(audits.findByApprovalIdOrderByCreatedAtAsc(id)).extracting(ApprovalAudit::getAction)
                .containsSubsequence("REQUESTED", "APPROVED", "EXECUTED");
    }

    @Test void approvingTwiceRunsTheToolOnce() {
        UUID id = request(runningTask());
        gate.approve(id, "owner", null);
        assertThatThrownBy(() -> gate.approve(id, "owner", null)).isInstanceOf(IllegalStateException.class);
        assertThat(TOOL.runs.get()).isEqualTo(1);
    }

    @Test void concurrentDoubleClickRunsTheToolOnce() throws Exception {
        UUID id = request(runningTask());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) fs.add(pool.submit(() -> {
            go.await();
            try { gate.approve(id, "owner", null); return true; } catch (RuntimeException e) { return false; }
        }));
        go.countDown();
        int ok = 0;
        for (var f : fs) if (f.get(30, TimeUnit.SECONDS)) ok++;
        pool.shutdown();
        assertThat(TOOL.runs.get()).as("tool chỉ chạy đúng 1 lần dù 8 request đua nhau").isEqualTo(1);
        assertThat(ok).isEqualTo(1);
    }

    @Test void rejectFailsTheTaskByDefaultAndCannotBeApprovedAfterwards() {
        Task t = runningTask();
        UUID id = request(t);
        gate.reject(id, "owner", "không đồng ý");
        Approval a = approvals.findById(id).orElseThrow();
        assertThat(a.getStatus()).isEqualTo(ApprovalStatus.REJECTED);
        assertThat(a.getReason()).isEqualTo("không đồng ý");
        assertThat(tasks.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThatThrownBy(() -> gate.approve(id, "owner", null)).isInstanceOf(IllegalStateException.class);
        assertThat(TOOL.runs.get()).isZero();
    }

    @Test void approvePastExpiryIsRefusedEvenBeforeTheSweeperRuns() {
        UUID id = request(runningTask());
        jdbc.update("UPDATE approval SET expires_at = now() - interval '1 minute' WHERE id = ?", id);
        assertThatThrownBy(() -> gate.approve(id, "owner", null)).isInstanceOf(IllegalStateException.class);
        assertThat(TOOL.runs.get()).isZero();
    }

    @Test void expireOverdueMarksExpiredFailsTheTaskAndAudits() {
        Task t = runningTask();
        UUID id = request(t);
        Task fresh = runningTask();
        UUID freshId = request(fresh);
        jdbc.update("UPDATE approval SET expires_at = now() - interval '1 minute' WHERE id = ?", id);
        assertThat(gate.expireOverdue()).isEqualTo(1);
        assertThat(approvals.findById(id).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.EXPIRED);
        assertThat(approvals.findById(freshId).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(tasks.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(audits.findByApprovalIdOrderByCreatedAtAsc(id)).extracting(ApprovalAudit::getAction).contains("EXPIRED");
        assertThat(gate.expireOverdue()).as("chạy lại không xử lý hai lần").isZero();
    }

    @Test void neverRunsAToolForACancelledTask() {
        Task t = runningTask();
        UUID id = request(t);
        taskService.cancel(t.getId());                                       // WAITING_APPROVAL -> CANCELLED ngay
        assertThatThrownBy(() -> gate.approve(id, "owner", null)).isInstanceOf(IllegalStateException.class);
        assertThat(TOOL.runs.get()).isZero();
    }
}
