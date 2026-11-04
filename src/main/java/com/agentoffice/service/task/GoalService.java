package com.agentoffice.service.task;

import com.agentoffice.domain.task.GoalStatus;
import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.dto.task.GoalDetailDto;
import com.agentoffice.dto.task.GoalDto;
import com.agentoffice.mapper.task.GoalMapper;
import com.agentoffice.mapper.task.TaskMapper;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.entity.task.Task;
import com.agentoffice.repository.task.GoalRepository;
import com.agentoffice.repository.task.TaskRepository;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.service.event.EventPublisher;
import com.agentoffice.domain.event.EventTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Vòng đời Goal (mọi đổi trạng thái goal đi qua đây, kèm event trong cùng transaction). */
@Service
public class GoalService {
    private static final Logger log = LoggerFactory.getLogger(GoalService.class);
    private static final Map<GoalStatus, Set<GoalStatus>> ALLOWED = new EnumMap<>(GoalStatus.class);
    static {
        ALLOWED.put(GoalStatus.DRAFT, Set.of(GoalStatus.PLANNING, GoalStatus.CANCELLED));
        ALLOWED.put(GoalStatus.PLANNING, Set.of(GoalStatus.AWAITING_PLAN_APPROVAL, GoalStatus.RUNNING, GoalStatus.FAILED, GoalStatus.CANCELLED));
        ALLOWED.put(GoalStatus.AWAITING_PLAN_APPROVAL, Set.of(GoalStatus.RUNNING, GoalStatus.PLANNING, GoalStatus.FAILED, GoalStatus.CANCELLED));
        ALLOWED.put(GoalStatus.RUNNING, Set.of(GoalStatus.SUCCEEDED, GoalStatus.FAILED, GoalStatus.CANCELLED));
        for (GoalStatus s : new GoalStatus[] {GoalStatus.SUCCEEDED, GoalStatus.FAILED, GoalStatus.CANCELLED}) ALLOWED.put(s, Set.of());
    }

    private final GoalRepository goals;
    private final TaskRepository tasks;
    private final TaskService taskService;
    private final EventPublisher events;
    private final Clock clock;
    private final GoalMapper goalMapper;
    private final TaskMapper taskMapper;

    public GoalService(GoalRepository goals, TaskRepository tasks, TaskService taskService, EventPublisher events, Clock clock,
                       GoalMapper goalMapper, TaskMapper taskMapper) {
        this.goalMapper = goalMapper;
        this.taskMapper = taskMapper;
        this.goals = goals;
        this.tasks = tasks;
        this.taskService = taskService;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public Goal create(String title, String description, UUID departmentId, Long tokenBudget) {
        Goal g = goals.saveAndFlush(new Goal(title, description, departmentId, tokenBudget));
        events.publish(EventTypes.GOAL_CREATED, g.getId(), payload(g));
        return g;
    }

    @Transactional
    public Goal move(UUID goalId, GoalStatus to) {
        Goal g = goals.findByIdForUpdate(goalId).orElseThrow(() -> new NotFoundException("goal", goalId));
        if (!ALLOWED.get(g.getStatus()).contains(to))
            throw new IllegalStateException("Goal không thể chuyển " + g.getStatus() + " -> " + to);
        GoalStatus from = g.getStatus();
        g.setStatus(to);
        log.info("goal {} {}->{}", goalId, from, to);
        if (to.isTerminal()) g.setFinishedAt(clock.instant());
        return goals.saveAndFlush(g);
    }

    @Transactional
    public Goal succeed(UUID goalId, String result) {
        Goal g = move(goalId, GoalStatus.SUCCEEDED);
        g.setResult(result);
        events.publish(EventTypes.GOAL_SUCCEEDED, goalId, payload(g));
        return g;
    }

    @Transactional
    public Goal fail(UUID goalId, String error) {
        Goal g = move(goalId, GoalStatus.FAILED);
        g.setError(error);
        taskService.cancelOpenTasksOfGoal(goalId);
        events.publish(EventTypes.GOAL_FAILED, goalId, payload(g));
        return g;
    }

    @Transactional
    public Goal cancel(UUID goalId) {
        Goal g = move(goalId, GoalStatus.CANCELLED);
        taskService.cancelOpenTasksOfGoal(goalId);
        events.publish(EventTypes.GOAL_CANCELLED, goalId, payload(g));
        return g;
    }

    @Transactional
    public void storePlan(UUID goalId, String planJson, boolean awaitingApproval, int taskCount) {
        Goal g = goals.findByIdForUpdate(goalId).orElseThrow(() -> new NotFoundException("goal", goalId));
        g.setPlan(planJson);
        goals.save(g);
        Map<String, Object> p = payload(g);
        p.put("taskCount", taskCount);
        p.put("awaitingApproval", awaitingApproval);
        events.publish(EventTypes.GOAL_PLAN_PROPOSED, goalId, p);
    }

    @Transactional
    public void notePlannerCall(UUID goalId) {
        Goal g = goals.findByIdForUpdate(goalId).orElseThrow(() -> new NotFoundException("goal", goalId));
        g.addPlanAttempt();
    }

    /** Tăng số lần chủ từ chối kế hoạch, trả số lần sau khi tăng. */
    @Transactional
    public int noteRejection(UUID goalId) {
        Goal g = goals.findByIdForUpdate(goalId).orElseThrow(() -> new NotFoundException("goal", goalId));
        g.addPlanRejection();
        return g.getPlanRejections();
    }

    /** Cộng token đã dùng; vượt tokenBudget thì dừng goal (FAILED + huỷ task còn lại) và phát goal.failed. */
    @Transactional
    public void recordTokens(UUID goalId, long tokens) {
        goals.addTokens(goalId, tokens);
        Goal g = goals.findById(goalId).orElseThrow(() -> new NotFoundException("goal", goalId));
        if (g.getStatus() == GoalStatus.RUNNING && g.getTokenBudget() != null && g.getTokensUsed() >= g.getTokenBudget())
            fail(goalId, "Vượt ngân sách token của goal (" + g.getTokensUsed() + "/" + g.getTokenBudget() + ")");
    }

    @Transactional(readOnly = true)
    public Goal get(UUID id) { return goals.findById(id).orElseThrow(() -> new NotFoundException("goal", id)); }

    @Transactional(readOnly = true)
    public List<GoalDto> list() {
        return goals.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"))
                .stream().map(goalMapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public GoalDetailDto detail(UUID id) {
        Goal g = get(id);
        List<Task> all = tasks.findByGoalIdOrderByCreatedAtAsc(id);
        Map<UUID, List<Task>> byParent = new HashMap<>();
        List<Task> roots = new ArrayList<>();
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        all.forEach(t -> ids.add(t.getId()));
        for (Task t : all) {
            if (t.getParentTaskId() == null || !ids.contains(t.getParentTaskId())) roots.add(t);
            else byParent.computeIfAbsent(t.getParentTaskId(), k -> new ArrayList<>()).add(t);
        }
        long counted = all.stream().filter(t -> t.getStatus() != TaskStatus.CANCELLED).count();
        long done = all.stream().filter(t -> t.getStatus() == TaskStatus.SUCCEEDED).count();
        int pct = g.getStatus() == GoalStatus.SUCCEEDED ? 100 : counted == 0 ? 0 : (int) (done * 100 / counted);
        return new GoalDetailDto(goalMapper.toDto(g), pct, roots.stream().map(r -> node(r, byParent)).toList(), g.getPlan());
    }

    private GoalDetailDto.TaskNode node(Task t, Map<UUID, List<Task>> byParent) {
        return new GoalDetailDto.TaskNode(taskMapper.toDto(t),
                byParent.getOrDefault(t.getId(), List.of()).stream().map(c -> node(c, byParent)).toList());
    }

    private Map<String, Object> payload(Goal g) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("goalId", g.getId());
        p.put("title", g.getTitle());
        p.put("status", g.getStatus());
        if (g.getError() != null) p.put("error", g.getError());
        return p;
    }
}
