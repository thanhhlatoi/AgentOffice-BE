package com.agentoffice.service.task;

import com.agentoffice.domain.task.GoalStatus;
import com.agentoffice.domain.task.NewTask;
import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.ProjectTouched;
import com.agentoffice.domain.task.TaskFinished;
import com.agentoffice.domain.task.TaskStateMachine;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.entity.task.Task;
import com.agentoffice.entity.task.TaskDependency;
import com.agentoffice.repository.task.GoalRepository;
import com.agentoffice.repository.task.TaskDependencyRepository;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.repository.agent.AgentRepository;
import com.agentoffice.config.LimitsProperties;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.exception.UnprocessableException;
import com.agentoffice.service.event.EventPublisher;
import com.agentoffice.domain.event.EventTypes;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * NƠI DUY NHẤT được đổi Task.status (setStatus là package-private + test kiến trúc canh).
 * Mỗi thay đổi: lưu DB + phát DomainEvent trong CÙNG transaction.
 */
@Service
public class TaskService {
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private final TaskRepository tasks;
    private final TaskDependencyRepository deps;
    private final AgentRepository agents;
    private final GoalRepository goals;
    private final TaskStateMachine stateMachine;
    private final EventPublisher events;
    private final ApplicationEventPublisher spring;
    private final LimitsProperties limits;
    private final Clock clock;

    public TaskService(TaskRepository tasks, TaskDependencyRepository deps, AgentRepository agents, GoalRepository goals,
                       TaskStateMachine stateMachine, EventPublisher events, ApplicationEventPublisher spring,
                       LimitsProperties limits, Clock clock) {
        this.tasks = tasks;
        this.deps = deps;
        this.agents = agents;
        this.goals = goals;
        this.stateMachine = stateMachine;
        this.events = events;
        this.spring = spring;
        this.limits = limits;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ tạo
    /** Tạo task ở trạng thái PENDING + event task.created. Kiểm giới hạn: agent bật, độ sâu, số task/goal. */
    @Transactional
    public Task create(NewTask c) {
        Agent a = agents.findById(c.assigneeAgentId()).orElseThrow(() -> new NotFoundException("agent", c.assigneeAgentId()));
        if (!a.isActive()) throw new UnprocessableException("Agent " + a.getName() + " đang OFFLINE/đã tắt, không nhận task");
        if (c.depth() > limits.maxTaskDepth())
            throw new UnprocessableException("Vượt độ sâu giao việc tối đa " + limits.maxTaskDepth());
        UUID projectId = null;
        if (c.goalId() != null) {
            // khoá hàng goal để 2 luồng cùng tạo task không vượt trần
            Goal g = goals.findByIdForUpdate(c.goalId()).orElseThrow(() -> new NotFoundException("goal", c.goalId()));
            if (g.getStatus().isTerminal()) throw new UnprocessableException("Goal đã kết thúc (" + g.getStatus() + ")");
            if (tasks.countByGoalId(c.goalId()) >= limits.maxTasksPerGoal())
                throw new UnprocessableException("Goal đã đạt tối đa " + limits.maxTasksPerGoal() + " task");
            projectId = g.getProjectId();                 // task mang dự án của goal
        }
        Task nt = new Task(c.goalId(), c.parentTaskId(), c.assigneeAgentId(), c.createdBy(),
                c.title(), c.input(), c.priority(), c.depth());
        nt.setProjectId(projectId);
        Task t = tasks.saveAndFlush(nt);
        if (projectId != null) spring.publishEvent(new ProjectTouched(projectId));   // mẫu số tiến độ vừa tăng
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("taskId", t.getId());
        p.put("goalId", t.getGoalId());
        p.put("assigneeAgentId", t.getAssigneeAgentId());
        p.put("createdBy", t.getCreatedBy());
        p.put("depth", t.getDepth());
        events.publish(EventTypes.TASK_CREATED, t.getId(), p);
        return t;
    }

    @Transactional
    public void addDependency(UUID taskId, UUID dependsOnTaskId) {
        if (taskId.equals(dependsOnTaskId)) throw new UnprocessableException("Task không thể phụ thuộc chính nó");
        deps.save(new TaskDependency(taskId, dependsOnTaskId));
    }

    /** PENDING -> QUEUED nếu mọi phụ thuộc đã SUCCEEDED, ngược lại PENDING -> BLOCKED. */
    @Transactional
    public Task settle(UUID taskId) {
        List<TaskDependency> d = deps.findByTaskId(taskId);
        boolean allDone = d.stream().allMatch(x ->
                tasks.findById(x.getDependsOnTaskId()).map(t -> t.getStatus() == TaskStatus.SUCCEEDED).orElse(false));
        return transition(taskId, allDone ? TaskStatus.QUEUED : TaskStatus.BLOCKED, d.isEmpty() ? "no dependencies" : "dependencies");
    }

    // ------------------------------------------------------------------ đổi trạng thái
    @Transactional
    public Task transition(UUID taskId, TaskStatus to, String reason) {
        Task t = tasks.findById(taskId).orElseThrow(() -> new NotFoundException("task", taskId));
        TaskStatus from = t.getStatus();
        stateMachine.require(from, to);                       // ném IllegalStateException nếu sai
        t.setStatus(to);
        Instant now = clock.instant();
        t.leaveStatus(from, now);
        if (to == TaskStatus.RUNNING && t.getStartedAt() == null) t.setStartedAt(now);
        if (to.isTerminal()) t.setFinishedAt(now);
        tasks.saveAndFlush(t);                                // lộ xung đột @Version ngay tại đây
        log.info("task {} {}->{} reason={}", taskId, from, to, abbreviate(reason));   // grep taskId = cả vòng đời

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("taskId", taskId);
        p.put("goalId", t.getGoalId());
        p.put("from", from);
        p.put("to", to);
        p.put("reason", reason);
        events.publish(EventTypes.TASK_STATE_CHANGED, taskId, p);
        if (to.isTerminal()) {
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("taskId", taskId);
            q.put("goalId", t.getGoalId());
            q.put("assigneeAgentId", t.getAssigneeAgentId());
            if (to == TaskStatus.FAILED) q.put("error", t.getError());
            events.publish(switch (to) {
                case SUCCEEDED -> EventTypes.TASK_SUCCEEDED;
                case FAILED -> EventTypes.TASK_FAILED;
                default -> EventTypes.TASK_CANCELLED;
            }, taskId, q);
            spring.publishEvent(new TaskFinished(taskId, t.getGoalId(), to));   // đồng bộ, cùng transaction
        }
        return t;
    }

    /** Lý do có thể là thông báo lỗi dài: cắt ngắn và bỏ xuống dòng để mỗi sự kiện đúng 1 dòng log. */
    private static String abbreviate(String s) {
        if (s == null) return "-";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 200 ? one : one.substring(0, 200) + "…";
    }

    @Transactional
    public Task complete(UUID taskId, String result) {
        Task t = tasks.findById(taskId).orElseThrow(() -> new NotFoundException("task", taskId));
        t.setResult(result);
        return transition(taskId, TaskStatus.SUCCEEDED, "completed");
    }

    @Transactional
    public Task fail(UUID taskId, String error) {
        Task t = tasks.findById(taskId).orElseThrow(() -> new NotFoundException("task", taskId));
        t.setError(error);
        return transition(taskId, TaskStatus.FAILED, error);
    }

    /** RUNNING -> QUEUED, attempts+1, hẹn giờ chạy lại. Gọi bởi dispatcher với nextRunAt do TaskScheduler.backoffSeconds tính. */
    @Transactional
    public Task scheduleRetry(UUID taskId, Instant nextRunAt, String error) {
        Task t = tasks.findById(taskId).orElseThrow(() -> new NotFoundException("task", taskId));
        t.setAttempts(t.getAttempts() + 1);
        t.setNextRunAt(nextRunAt);
        t.setError(error);
        return transition(taskId, TaskStatus.QUEUED, "retry #" + t.getAttempts() + ": " + error);
    }

    // ------------------------------------------------------------------ ngân sách
    /** Hết ngân sách giữa chừng: giữ nguyên attempts (không phải lần thử lại), nhớ ngân sách nào chặn và cần bao nhiêu. */
    @Transactional
    public Task waitForBudget(UUID taskId, UUID budgetId, long needMicro) {
        Task t = tasks.findById(taskId).orElseThrow(() -> new NotFoundException("task", taskId));
        t.setWaitingBudget(budgetId, needMicro);
        return transition(taskId, TaskStatus.WAITING_BUDGET, "budget exceeded: " + budgetId);
    }

    /** WAITING_BUDGET -> QUEUED khi hạn mức tăng hoặc sang kỳ mới (BudgetService quyết định khi nào). */
    @Transactional
    public Task resumeFromBudget(UUID taskId) {
        Task t = tasks.findById(taskId).orElseThrow(() -> new NotFoundException("task", taskId));
        UUID budgetId = t.getWaitingBudgetId();
        t.setWaitingBudget(null, null);
        t.setNextRunAt(null);
        Task r = transition(taskId, TaskStatus.QUEUED, "budget available again");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("taskId", taskId);
        p.put("goalId", t.getGoalId());
        p.put("budgetId", budgetId);
        events.publish(EventTypes.BUDGET_RESUMED, taskId, p);
        return r;
    }

    // ------------------------------------------------------------------ huỷ
    /** Huỷ hợp tác: task chưa chạy huỷ ngay (+ con chưa chạy huỷ theo); task RUNNING chỉ đặt cờ cancelRequested. */
    @Transactional
    public Task cancel(UUID taskId) {
        Task t = tasks.findById(taskId).orElseThrow(() -> new NotFoundException("task", taskId));
        if (t.getStatus().isTerminal()) throw new IllegalStateException("Task đã kết thúc: " + t.getStatus());
        if (t.getStatus() == TaskStatus.RUNNING) {
            t.setCancelRequested(true);
            tasks.saveAndFlush(t);
        } else {
            t.setCancelRequested(true);
            transition(taskId, TaskStatus.CANCELLED, "cancelled");
        }
        for (Task c : tasks.findByParentTaskId(taskId)) cancelIfNotStarted(c);
        return tasks.findById(taskId).orElseThrow();
    }

    private void cancelIfNotStarted(Task c) {
        switch (c.getStatus()) {
            case PENDING, BLOCKED, QUEUED, WAITING_BUDGET -> {
                c.setCancelRequested(true);
                transition(c.getId(), TaskStatus.CANCELLED, "parent cancelled");
            }
            case RUNNING, WAITING_APPROVAL -> { c.setCancelRequested(true); tasks.saveAndFlush(c); }
            default -> { }
        }
        for (Task g : tasks.findByParentTaskId(c.getId())) cancelIfNotStarted(g);
    }

    /** Huỷ mọi task chưa kết thúc của goal (task RUNNING nhận cờ huỷ). */
    @Transactional
    public int cancelOpenTasksOfGoal(UUID goalId) {
        int n = 0;
        for (Task t : tasks.findByGoalIdOrderByCreatedAtAsc(goalId)) {
            if (t.getStatus().isTerminal()) continue;
            if (t.getStatus() == TaskStatus.RUNNING) { t.setCancelRequested(true); tasks.saveAndFlush(t); }
            else transition(t.getId(), TaskStatus.CANCELLED, "goal cancelled");
            n++;
        }
        return n;
    }

    /** Thử lại thủ công: task FAILED không đi đâu nữa (state machine) nên tạo BẢN SAO mới; chỉ cho task lẻ hoặc goal còn RUNNING. */
    @Transactional
    public Task retry(UUID taskId) {
        Task old = tasks.findById(taskId).orElseThrow(() -> new NotFoundException("task", taskId));
        if (old.getStatus() != TaskStatus.FAILED) throw new IllegalStateException("Chỉ thử lại được task FAILED, hiện là " + old.getStatus());
        return copyForRerun(old);
    }

    /** Kanban kéo thẻ SUCCEEDED/FAILED về "waiting": chạy lại bằng task MỚI (retryOfTaskId), task cũ giữ nguyên làm lịch sử. */
    @Transactional
    public Task rerun(UUID taskId) {
        Task old = tasks.findById(taskId).orElseThrow(() -> new NotFoundException("task", taskId));
        if (old.getStatus() != TaskStatus.FAILED && old.getStatus() != TaskStatus.SUCCEEDED)
            throw new IllegalStateException("Chỉ chạy lại được task SUCCEEDED/FAILED, hiện là " + old.getStatus());
        return copyForRerun(old);
    }

    private Task copyForRerun(Task old) {
        if (old.getGoalId() != null && goals.findById(old.getGoalId()).map(g -> g.getStatus() != GoalStatus.RUNNING).orElse(true))
            throw new IllegalStateException("Goal của task không còn RUNNING nên không thể thử lại");
        Task copy = create(new NewTask(old.getGoalId(), old.getParentTaskId(), old.getAssigneeAgentId(), "HUMAN",
                old.getTitle(), old.getInput(), old.getPriority(), old.getDepth()));
        copy.setRetryOfTaskId(old.getId());
        copy.setWeight(old.getWeight());
        copy.setLabels(old.getLabels());
        tasks.saveAndFlush(copy);
        return settle(copy.getId());
    }

    /** Đổi weight (1..5) / nhãn. Weight là mẫu số tiến độ nên báo dự án tính lại. */
    @Transactional
    public Task updateMeta(UUID taskId, Integer weight, List<String> labels) {
        Task t = tasks.findById(taskId).orElseThrow(() -> new NotFoundException("task", taskId));
        if (weight != null) {
            if (weight < 1 || weight > 5) throw new UnprocessableException("weight phải trong 1..5");
            t.setWeight(weight);
        }
        if (labels != null) t.setLabels(labels);
        tasks.saveAndFlush(t);
        if (t.getProjectId() != null) spring.publishEvent(new ProjectTouched(t.getProjectId()));
        return t;
    }

    @Transactional
    public Task changePriority(UUID taskId, Priority p) {
        Task t = tasks.findById(taskId).orElseThrow(() -> new NotFoundException("task", taskId));
        if (t.getStatus().isTerminal()) throw new IllegalStateException("Task đã kết thúc: " + t.getStatus());
        t.setPriority(p);
        return tasks.saveAndFlush(t);
    }

    // ------------------------------------------------------------------ đọc
    @Transactional(readOnly = true)
    public Task get(UUID id) { return tasks.findById(id).orElseThrow(() -> new NotFoundException("task", id)); }

    @Transactional(readOnly = true)
    public Page<Task> search(TaskStatus status, UUID agentId, UUID goalId, int page, int size) {
        Specification<Task> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (status != null) ps.add(cb.equal(root.get("status"), status));
            if (agentId != null) ps.add(cb.equal(root.get("assigneeAgentId"), agentId));
            if (goalId != null) ps.add(cb.equal(root.get("goalId"), goalId));
            return cb.and(ps.toArray(Predicate[]::new));
        };
        return tasks.findAll(spec, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }
}
