package com.agentoffice.controller.task;

import jakarta.servlet.http.HttpServletResponse;

import com.agentoffice.controller.support.Paging;

import com.agentoffice.dto.task.GoalDetailDto;
import com.agentoffice.dto.task.GoalDto;
import com.agentoffice.mapper.task.GoalMapper;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.service.task.GoalService;
import com.agentoffice.security.SensitiveAction;
import com.agentoffice.service.task.OrchestratorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/goals")
public class GoalController {
    public record CreateGoal(@NotBlank @Size(max = 300) String title, @Size(max = 5000) String description,
                             UUID departmentId, @PositiveOrZero Long tokenBudget) {}
    public record RejectPlan(@NotBlank @Size(max = 1000) String reason) {}

    private final GoalService goals;
    private final OrchestratorService orchestrator;

    private final GoalMapper mapper;

    public GoalController(GoalService goals, OrchestratorService orchestrator, GoalMapper mapper) {
        this.mapper = mapper;
        this.goals = goals;
        this.orchestrator = orchestrator;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GoalDto create(@Valid @RequestBody CreateGoal r) {
        Goal g = goals.create(r.title(), r.description(), r.departmentId(), r.tokenBudget());
        orchestrator.planAsync(g.getId(), List.of());          // Planner chạy nền; FE theo dõi qua SSE
        return mapper.toDto(g);
    }

    @GetMapping
    public List<GoalDto> list(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size, HttpServletResponse res) { return Paging.slice(goals.list(), page, size, res); }

    @GetMapping("/{id}")
    public GoalDetailDto get(@PathVariable UUID id) { return goals.detail(id); }

    @PostMapping("/{id}/cancel")
    @SensitiveAction("goal.cancel")
    public GoalDto cancel(@PathVariable UUID id) {
        orchestrator.cancelGoal(id, "owner");
        return mapper.toDto(goals.get(id));
    }

    @PostMapping("/{id}/plan/approve")
    @SensitiveAction("goal.plan.approve")
    public GoalDto approvePlan(@PathVariable UUID id) {
        orchestrator.approvePlan(id, "owner", null);
        return mapper.toDto(goals.get(id));
    }

    @PostMapping("/{id}/plan/reject")
    @SensitiveAction("goal.plan.reject")
    public GoalDto rejectPlan(@PathVariable UUID id, @Valid @RequestBody RejectPlan r) {
        var outcome = orchestrator.rejectPlan(id, "owner", r.reason());
        if (outcome.replan()) orchestrator.planAsync(id, List.of(r.reason()));
        return mapper.toDto(goals.get(id));
    }
}
