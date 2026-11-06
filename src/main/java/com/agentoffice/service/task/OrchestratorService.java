package com.agentoffice.service.task;

import com.agentoffice.config.ApprovalProperties;
import com.agentoffice.domain.approval.ApprovalKind;
import com.agentoffice.domain.approval.ApprovalStatus;
import com.agentoffice.domain.task.AgentInfo;
import com.agentoffice.domain.task.GoalStatus;
import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Plan;
import com.agentoffice.domain.task.PlanPreview;
import com.agentoffice.domain.task.PlanValidator;
import com.agentoffice.domain.task.PlannerException;
import com.agentoffice.domain.task.TaskFinished;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.entity.approval.Approval;
import com.agentoffice.entity.approval.ApprovalAudit;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.entity.task.Task;
import com.agentoffice.repository.approval.ApprovalAuditRepository;
import com.agentoffice.repository.approval.ApprovalRepository;
import com.agentoffice.repository.task.GoalRepository;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.repository.agent.AgentRepository;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.service.event.EventPublisher;
import com.agentoffice.domain.event.EventTypes;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentoffice.util.LogContext;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Điều phối vòng đời goal: lập kế hoạch (Planner + PlanValidator, làm lại tối đa 2 lần), duyệt kế hoạch,
 * tạo cây task, tổng hợp kết quả khi mọi task xong.
 * Planner được gọi NGOÀI transaction (có thể chậm); mỗi bước ghi DB là một transaction ngắn.
 */
@Service
public class OrchestratorService {
    private static final Logger log = LoggerFactory.getLogger(OrchestratorService.class);
    static final int MAX_PLANNER_CALLS = 3;      // 1 lần đầu + 2 lần làm lại
    static final int MAX_REJECTION_REPLANS = 2;

    public record RejectOutcome(boolean replan) {}

    private final Planner planner;
    private final PlanValidator validator;
    private final GoalService goalService;
    private final GoalRepository goals;
    private final TaskService taskService;
    private final TaskRepository tasks;
    private final AgentRepository agents;
    private final ApprovalRepository approvals;
    private final ApprovalAuditRepository audits;
    private final ApprovalProperties approvalProps;
    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final ExecutorService planningPool = Executors.newFixedThreadPool(2);

    public OrchestratorService(Planner planner, PlanValidator validator, GoalService goalService, GoalRepository goals,
                               TaskService taskService, TaskRepository tasks, AgentRepository agents,
                               ApprovalRepository approvals, ApprovalAuditRepository audits, ApprovalProperties approvalProps,
                               EventPublisher events, ObjectMapper mapper, Clock clock, PlatformTransactionManager txm) {
        this.planner = planner;
        this.validator = validator;
        this.goalService = goalService;
        this.goals = goals;
        this.taskService = taskService;
        this.tasks = tasks;
        this.agents = agents;
        this.approvals = approvals;
        this.audits = audits;
        this.approvalProps = approvalProps;
        this.events = events;
        this.mapper = mapper;
        this.clock = clock;
        this.tx = new TransactionTemplate(txm);
    }

    // ------------------------------------------------------------------ lập kế hoạch
    public void planAsync(UUID goalId, List<String> feedback) {
        planningPool.submit(() -> {
            try (LogContext ignored = LogContext.with("requestId", "plan-" + goalId.toString().substring(0, 8), "goalId", goalId)) {
                try { plan(goalId, feedback); }
                catch (RuntimeException e) {
                    log.error("Lập kế hoạch lỗi", e);
                    try { tx.executeWithoutResult(s -> goalService.fail(goalId, "Lỗi lập kế hoạch: " + e.getClass().getSimpleName())); }
                    catch (RuntimeException failEx) {
                        log.warn("Không đánh dấu được goal FAILED (có thể đã kết thúc/huỷ trong lúc lập kế hoạch): {}", failEx.toString());
                    }
                }
            }
        });
    }

    /** Đồng bộ (test gọi trực tiếp). feedback rỗng = lần đầu; không rỗng = làm lại sau khi chủ từ chối. */
    public void plan(UUID goalId, List<String> feedback) {
        Goal start = goalService.get(goalId);
        if (start.getStatus() != GoalStatus.PLANNING) tx.executeWithoutResult(s -> goalService.move(goalId, GoalStatus.PLANNING));

        List<String> fb = feedback;
        Plan plan = null;
        List<String> errors = List.of();
        List<AgentInfo> infos = List.of();
        for (int attempt = 0; attempt < MAX_PLANNER_CALLS; attempt++) {
            tx.executeWithoutResult(s -> goalService.notePlannerCall(goalId));
            Goal goal = goalService.get(goalId);
            if (goal.getStatus() != GoalStatus.PLANNING) return;          // bị huỷ trong lúc chờ Planner
            infos = activeAgents();
            try {
                Plan p = fb.isEmpty() ? planner.propose(goal, infos) : planner.revise(goal, infos, fb);
                errors = validator.validate(p, infos);
                if (errors.isEmpty()) { plan = p; break; }
            } catch (PlannerException e) {
                errors = List.of(e.getMessage());
            }
            fb = errors;
        }
        final Plan accepted = plan;
        final List<String> lastErrors = errors;
        final List<AgentInfo> usedAgents = infos;
        tx.executeWithoutResult(s -> {
            if (goalService.get(goalId).getStatus() != GoalStatus.PLANNING) return;
            if (accepted == null) goalService.fail(goalId, "Kế hoạch không hợp lệ sau " + MAX_PLANNER_CALLS + " lần: " + String.join("; ", lastErrors));
            else accept(goalId, accepted, usedAgents);
        });
    }

    private void accept(UUID goalId, Plan plan, List<AgentInfo> agentInfos) {
        Goal g = goalService.get(goalId);
        String json = toJson(plan);
        if (approvalProps.requirePlanApproval()) {
            goalService.move(goalId, GoalStatus.AWAITING_PLAN_APPROVAL);
            goalService.storePlan(goalId, json, true, plan.tasks().size());
            Approval a = approvals.saveAndFlush(Approval.plan(goalId, json, PlanPreview.render(g.getTitle(), plan, agentInfos),
                    "plan:" + goalId + ":" + (g.getPlanAttempts()) + ":" + UUID.randomUUID(), clock.instant().plus(approvalProps.ttl())));
            audit(a.getId(), "orchestrator", "REQUESTED", Map.of("goalId", goalId));
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("approvalId", a.getId());
            p.put("kind", ApprovalKind.PLAN);
            p.put("goalId", goalId);
            p.put("preview", a.getPreview());
            events.publish(EventTypes.APPROVAL_REQUESTED, a.getId(), p);
        } else {
            goalService.storePlan(goalId, json, false, plan.tasks().size());
            startRunning(goalId, plan);
        }
    }

    /** Goal -> RUNNING rồi tạo cây task + phụ thuộc trong CÙNG transaction, xếp QUEUED/BLOCKED. */
    private void startRunning(UUID goalId, Plan plan) {
        goalService.move(goalId, GoalStatus.RUNNING);
        createTaskTree(goalId, plan);
    }

    // ------------------------------------------------------------------ duyệt kế hoạch
    @Transactional
    public void approvePlan(UUID goalId, String actor, String note) {
        goalService.move(goalId, GoalStatus.RUNNING);                      // khoá hàng goal: duyệt 2 lần => lần 2 ném IllegalState (409)
        Approval a = pendingPlan(goalId);
        if (a.getExpiresAt().isBefore(clock.instant())) throw new IllegalStateException("Yêu cầu duyệt kế hoạch đã hết hạn");
        resolve(a, ApprovalStatus.APPROVED, actor, note);
        audit(a.getId(), actor, "APPROVED", note == null ? Map.of() : Map.of("note", note));
        resolvedEvent(a);
        Plan plan = fromJson(a.getArgsSnapshot());
        createTaskTree(goalId, plan);
    }

    private void createTaskTree(UUID goalId, Plan plan) {
        Map<String, Task> byKey = new LinkedHashMap<>();
        for (Plan.SubTask st : plan.tasks())
            byKey.put(st.key(), taskService.create(new NewTask(goalId, null, st.assigneeAgentId(), "ORCHESTRATOR",
                    st.title(), st.input(), st.priority(), 0)));
        for (Plan.SubTask st : plan.tasks())
            for (String dep : st.dependsOnKeys()) taskService.addDependency(byKey.get(st.key()).getId(), byKey.get(dep).getId());
        for (Task t : byKey.values()) taskService.settle(t.getId());   // sau khi đủ phụ thuộc: QUEUED hoặc BLOCKED
    }

    /** Từ chối kế hoạch: tối đa 2 lần làm lại; lần thứ 3 bị từ chối thì goal FAILED. Trả replan=true nếu caller nên gọi planAsync. */
    @Transactional
    public RejectOutcome rejectPlan(UUID goalId, String actor, String reason) {
        goalService.move(goalId, GoalStatus.PLANNING);
        Approval a = pendingPlan(goalId);
        resolve(a, ApprovalStatus.REJECTED, actor, reason);
        audit(a.getId(), actor, "REJECTED", Map.of("reason", reason));
        resolvedEvent(a);
        int n = goalService.noteRejection(goalId);
        if (n > MAX_REJECTION_REPLANS) {
            goalService.fail(goalId, "Chủ đã từ chối kế hoạch " + n + " lần: " + reason);
            return new RejectOutcome(false);
        }
        return new RejectOutcome(true);
    }

    @Transactional
    public void cancelGoal(UUID goalId, String actor) {
        goalService.cancel(goalId);
        approvals.findFirstByGoalIdAndKindAndStatus(goalId, ApprovalKind.PLAN, ApprovalStatus.PENDING).ifPresent(a -> {
            resolve(a, ApprovalStatus.REJECTED, actor, "goal cancelled");
            audit(a.getId(), actor, "REJECTED", Map.of("reason", "goal cancelled"));
            resolvedEvent(a);
        });
    }

    /** Job mỗi phút: kế hoạch chờ duyệt quá hạn => EXPIRED và goal FAILED. (Lời gọi tool hết hạn là việc của ApprovalGate.) */
    @Transactional
    public int expirePlans() {
        int n = 0;
        for (Approval a : approvals.findByStatusAndKindAndExpiresAtBefore(ApprovalStatus.PENDING, ApprovalKind.PLAN, clock.instant())) {
            resolve(a, ApprovalStatus.EXPIRED, "system", "expired");
            audit(a.getId(), "system", "EXPIRED", Map.of());
            resolvedEvent(a);
            goalService.fail(a.getGoalId(), "Kế hoạch hết hạn chờ duyệt");
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ tổng hợp kết quả
    /** Chạy ĐỒNG BỘ sau TaskFinishedListener (@Order 1), cùng transaction. Khoá hàng goal để 2 task xong cùng lúc không bỏ sót. */
    @EventListener
    @Order(2)
    public void onTaskFinished(TaskFinished e) {
        if (e.goalId() == null) return;
        Goal g = goals.findByIdForUpdate(e.goalId()).orElse(null);
        if (g == null || g.getStatus() != GoalStatus.RUNNING) return;
        List<Task> all = tasks.findByGoalIdOrderByCreatedAtAsc(e.goalId());
        if (all.stream().anyMatch(t -> !t.getStatus().isTerminal())) return;
        List<Task> bad = all.stream().filter(t -> t.getStatus() != TaskStatus.SUCCEEDED).toList();
        if (bad.isEmpty()) {
            goalService.succeed(g.getId(), aggregate(all));
        } else {
            goalService.fail(g.getId(), "Task không hoàn thành: " + bad.stream()
                    .map(t -> t.getTitle() + " (" + t.getStatus() + ")").reduce((a, b) -> a + "; " + b).orElse(""));
        }
    }

    /** Tổng hợp tất định: nối kết quả từng task theo thứ tự tạo. (Tổng hợp bằng LLM để sau.) */
    static String aggregate(List<Task> all) {
        StringBuilder sb = new StringBuilder();
        for (Task t : all) {
            if (t.getResult() == null) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("## ").append(t.getTitle()).append('\n').append(t.getResult());
        }
        return sb.length() > 20000 ? sb.substring(0, 20000) : sb.toString();
    }

    // ------------------------------------------------------------------ nội bộ
    private List<AgentInfo> activeAgents() {
        return agents.findAll().stream().filter(a -> a.isActive())
                .map(a -> new AgentInfo(a.getId(), a.getName(), a.getRole(), a.getDepartment(), a.getStatus(),
                        a.getSkills() == null ? List.of() : List.copyOf(a.getSkills()))).toList();
    }

    private Approval pendingPlan(UUID goalId) {
        return approvals.findFirstByGoalIdAndKindAndStatus(goalId, ApprovalKind.PLAN, ApprovalStatus.PENDING)
                .orElseThrow(() -> new IllegalStateException("Goal không có kế hoạch nào đang chờ duyệt"));
    }

    private void resolve(Approval a, ApprovalStatus s, String actor, String reason) {
        log.info("approval {} kind={} -> {} by {}", a.getId(), a.getKind(), s, actor);
        a.setStatus(s);
        a.setDecidedBy(actor);
        a.setDecidedAt(clock.instant());
        a.setReason(reason);
        approvals.save(a);
    }

    private void resolvedEvent(Approval a) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("approvalId", a.getId());
        p.put("kind", a.getKind());
        p.put("goalId", a.getGoalId());
        p.put("status", a.getStatus());
        events.publish(EventTypes.APPROVAL_RESOLVED, a.getId(), p);
    }

    private void audit(UUID approvalId, String actor, String action, Map<String, Object> detail) {
        audits.save(new ApprovalAudit(approvalId, actor, action, toJson(detail)));
    }

    private String toJson(Object o) {
        try { return mapper.writeValueAsString(o); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException(e); }
    }

    private Plan fromJson(String json) {
        try { return mapper.readValue(json, Plan.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Kế hoạch đã lưu bị hỏng", e); }
    }

    @PreDestroy
    void shutdown() { planningPool.shutdown(); }
}
