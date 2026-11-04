package com.agentoffice.config.dev;

import com.agentoffice.entity.agent.Agent;
import com.agentoffice.repository.agent.AgentRepository;
import com.agentoffice.service.task.OrchestratorService;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.service.task.GoalService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Dữ liệu mẫu cho dev: chỉ chạy khi DB chưa có agent nào. Ngoại lệ có chủ đích: được phụ thuộc nhiều feature. */
@Profile("dev")
@Component
public class DevDataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);
    private final AgentRepository agents;
    private final GoalService goals;
    private final OrchestratorService orchestrator;

    public DevDataSeeder(AgentRepository agents, GoalService goals, OrchestratorService orchestrator) {
        this.agents = agents;
        this.goals = goals;
        this.orchestrator = orchestrator;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (agents.count() > 0) return;
        agents.saveAll(List.of(new Agent("Linh", "Demand Planner", "Tai chinh"), new Agent("Minh", "Analyst", "Tai chinh"),
                new Agent("An", "Writer", "Content")));
        Goal g = goals.create("Lập báo cáo Q4 (mẫu dev)", "Tổng hợp doanh thu", null, 100_000L);
        orchestrator.planAsync(g.getId(), List.of());
        log.info("DevDataSeeder: tạo 3 agent + goal mẫu {}; duyệt kế hoạch bằng POST /api/goals/{}/plan/approve", g.getId(), g.getId());
    }
}
