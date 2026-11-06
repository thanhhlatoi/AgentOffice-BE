package com.agentoffice.service.fe;

import com.agentoffice.domain.approval.ApprovalKind;
import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.dto.fe.OfficeDto;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.entity.approval.Approval;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.entity.task.Task;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.repository.approval.ApprovalRepository;
import com.agentoffice.service.agent.AgentService;
import com.agentoffice.service.approval.ApprovalGate;
import com.agentoffice.service.system.SystemStateService;
import com.agentoffice.service.task.GoalService;
import com.agentoffice.service.task.OrchestratorService;
import com.agentoffice.service.task.TaskService;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Các lệnh ghi của FE. Chỉ đổi dạng dữ liệu vào rồi gọi service nghiệp vụ có sẵn (mọi quy tắc, event, giao dịch vẫn ở đó).
 * Không có @Transactional ở đây: mỗi service con tự mở giao dịch của nó.
 */
@Service
public class CommandService {
    private static final String OWNER = "owner";
    private static final String DEFAULT_DEPARTMENT = "Chung";

    private final AgentService agents;
    private final GoalService goals;
    private final OrchestratorService orchestrator;
    private final TaskService tasks;
    private final ApprovalRepository approvals;
    private final ApprovalGate gate;
    private final SystemStateService system;
    private final JdbcTemplate jdbc;

    public CommandService(AgentService agents, GoalService goals, OrchestratorService orchestrator, TaskService tasks,
                            ApprovalRepository approvals, ApprovalGate gate, SystemStateService system, JdbcTemplate jdbc) {
        this.agents = agents;
        this.goals = goals;
        this.orchestrator = orchestrator;
        this.tasks = tasks;
        this.approvals = approvals;
        this.gate = gate;
        this.system = system;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ agents
    public UUID createAgent(OfficeDto.AgentInput in) {
        String dept = departmentName(in.departmentId());
        Agent a = agents.create(in.name(), in.role(), dept == null ? DEFAULT_DEPARTMENT : dept, profile(in));
        return a.getId();
    }

    public UUID updateAgent(UUID id, OfficeDto.AgentInput in) {
        return agents.update(id, in.name(), in.role(), departmentName(in.departmentId()), profile(in)).getId();
    }

    /** Trả tên bot Hermes đã xoá kèm (null nếu agent không có bot). */
    public String deleteAgent(UUID id) { return agents.archive(id); }

    private static AgentService.Profile profile(OfficeDto.AgentInput in) {
        return new AgentService.Profile(in.systemPrompt(), in.model(), in.color(), in.skills());
    }

    private String departmentName(String departmentId) {
        if (departmentId == null || departmentId.isBlank()) return null;
        UUID id = parse(departmentId);
        List<String> names = jdbc.queryForList("SELECT name FROM department WHERE id = ?", String.class, id);
        if (names.isEmpty()) throw new NotFoundException("department", departmentId);
        return names.get(0);
    }

    // ------------------------------------------------------------------ goals
    /** requirePlanApproval là cấu hình toàn hệ thống (agentoffice.approval.require-plan-approval), không đặt riêng từng goal được. */
    public UUID createGoal(OfficeDto.GoalInput in) {
        if (in.title() == null || in.title().isBlank()) throw new AppException(ErrorCode.VALIDATION, "Tiêu đề là bắt buộc");
        UUID dept = in.departmentId() == null || in.departmentId().isBlank() ? null : parse(in.departmentId());
        Goal g = goals.create(in.title(), in.description(), dept, in.tokenBudget());
        orchestrator.planAsync(g.getId(), List.of());   // Planner chạy nền; FE nhận kết quả qua realtime
        return g.getId();
    }

    public void approvePlan(UUID goalId) { orchestrator.approvePlan(goalId, OWNER, null); }

    public void rejectPlan(UUID goalId, String reason) {
        if (reason == null || reason.isBlank()) throw new AppException(ErrorCode.VALIDATION, "Cần nhập lý do từ chối");
        if (orchestrator.rejectPlan(goalId, OWNER, reason).replan()) orchestrator.planAsync(goalId, List.of(reason));
    }

    public void cancelGoal(UUID goalId) { orchestrator.cancelGoal(goalId, OWNER); }

    // ------------------------------------------------------------------ tasks
    public UUID createTask(UUID agentId, OfficeDto.TaskBody body) {
        if (body.title() == null || body.title().isBlank()) throw new AppException(ErrorCode.VALIDATION, "Tiêu đề là bắt buộc");
        Task t = tasks.create(new NewTask(null, null, agentId, "HUMAN", body.title(), body.input(), Priority.NORMAL, 0));
        return tasks.settle(t.getId()).getId();
    }

    public UUID cancelTask(UUID id) { return tasks.cancel(id).getId(); }

    public UUID retryTask(UUID id) { return tasks.retry(id).getId(); }

    public UUID setPriority(UUID id, String priority) {
        try {
            return tasks.changePriority(id, Priority.valueOf(priority.toUpperCase(java.util.Locale.ROOT))).getId();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AppException(ErrorCode.VALIDATION, "priority phải là HIGH, NORMAL hoặc LOW");
        }
    }

    // ------------------------------------------------------------------ approvals
    public void approve(UUID id) {
        Approval a = approvals.findById(id).orElseThrow(() -> new NotFoundException("approval", id));
        if (a.getKind() == ApprovalKind.PLAN) orchestrator.approvePlan(a.getGoalId(), OWNER, null);
        else gate.approve(id, OWNER, null);
    }

    public void reject(UUID id, String reason) {
        if (reason == null || reason.isBlank()) throw new AppException(ErrorCode.VALIDATION, "Cần nhập lý do từ chối");
        Approval a = approvals.findById(id).orElseThrow(() -> new NotFoundException("approval", id));
        if (a.getKind() == ApprovalKind.PLAN) {
            if (orchestrator.rejectPlan(a.getGoalId(), OWNER, reason).replan()) orchestrator.planAsync(a.getGoalId(), List.of(reason));
        } else {
            gate.reject(id, OWNER, reason);
        }
    }

    // ------------------------------------------------------------------ system
    public void pause() { system.pause(OWNER); }

    public void resume() { system.resume(OWNER); }

    private static UUID parse(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.VALIDATION, "Id không hợp lệ: " + s);
        }
    }
}
