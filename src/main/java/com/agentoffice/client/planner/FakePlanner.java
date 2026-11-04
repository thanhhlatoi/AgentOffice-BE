package com.agentoffice.client.planner;

import com.agentoffice.domain.task.AgentInfo;
import com.agentoffice.domain.task.Plan;
import com.agentoffice.service.task.Planner;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.domain.task.Priority;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Planner tất định cho dev/test (mặc định khi chưa cấu hình): 2 task nối tiếp, giao cho agent đầu và agent cuối. */
@Component
@ConditionalOnProperty(name = "agentoffice.planner", havingValue = "fake", matchIfMissing = true)
public class FakePlanner implements Planner {
    @Override
    public Plan propose(Goal goal, List<AgentInfo> agents) {
        if (agents.isEmpty()) return new Plan(List.of());
        AgentInfo first = agents.get(0), last = agents.get(agents.size() - 1);
        return new Plan(List.of(
                new Plan.SubTask("analyze", "Phân tích: " + goal.getTitle(), goal.getDescription() == null ? goal.getTitle() : goal.getDescription(),
                        first.id(), Priority.NORMAL, List.of()),
                new Plan.SubTask("summarize", "Tổng hợp: " + goal.getTitle(), "Tổng hợp kết quả phân tích",
                        last.id(), Priority.NORMAL, List.of("analyze"))));
    }
}
