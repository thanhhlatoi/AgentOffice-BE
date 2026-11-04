package com.agentoffice.service.usage;

import com.agentoffice.domain.usage.CallContext;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.entity.task.Task;
import com.agentoffice.repository.agent.AgentRepository;
import com.agentoffice.repository.task.GoalRepository;
import com.agentoffice.repository.task.TaskRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Điền phần ngữ cảnh mà người gọi chưa biết: department (từ agent) và project (từ goal, nếu không thì từ task). */
@Service
public class CallContextEnricher {
    private final AgentRepository agents;
    private final GoalRepository goals;
    private final TaskRepository tasks;

    public CallContextEnricher(AgentRepository agents, GoalRepository goals, TaskRepository tasks) {
        this.agents = agents;
        this.goals = goals;
        this.tasks = tasks;
    }

    @Transactional(readOnly = true)
    public CallContext enrich(CallContext c) {
        UUID dep = c.departmentId();
        UUID project = c.projectId();
        if (dep == null && c.agentId() != null) dep = agents.findById(c.agentId()).map(Agent::getDepartmentId).orElse(null);
        if (project == null && c.goalId() != null) project = goals.findById(c.goalId()).map(Goal::getProjectId).orElse(null);
        if (project == null && c.taskId() != null) project = tasks.findById(c.taskId()).map(Task::getProjectId).orElse(null);
        if (dep == c.departmentId() && project == c.projectId()) return c;
        return new CallContext(c.agentId(), c.taskId(), c.goalId(), project, dep, c.purpose(), c.requestId());
    }
}
