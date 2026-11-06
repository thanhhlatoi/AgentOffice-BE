package com.agentoffice.client.planner;

import com.agentoffice.config.RuntimeAiConfig;
import com.agentoffice.domain.task.AgentInfo;
import com.agentoffice.domain.task.Plan;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.service.task.Planner;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Planner mà OrchestratorService dùng: chọn Hermes / LLM / giả THEO CẤU HÌNH ĐANG CHẠY ở mỗi lần lập kế hoạch,
 * nên đổi "Bên lập kế hoạch" trong Cài đặt AI có hiệu lực ngay, không cần khởi động lại BE.
 */
@Component
@Primary
public class SwitchingPlanner implements Planner {
    private final RuntimeAiConfig cfg;
    private final HermesPlanner hermes;
    private final LlmPlanner llm;
    private final FakePlanner fake;

    public SwitchingPlanner(RuntimeAiConfig cfg, HermesPlanner hermes, LlmPlanner llm, FakePlanner fake) {
        this.cfg = cfg;
        this.hermes = hermes;
        this.llm = llm;
        this.fake = fake;
    }

    private Planner current() {
        return switch (cfg.get().plannerMode()) {
            case "hermes" -> hermes;
            case "llm" -> llm;
            default -> fake;
        };
    }

    @Override
    public Plan propose(Goal goal, List<AgentInfo> agents) { return current().propose(goal, agents); }

    @Override
    public Plan revise(Goal goal, List<AgentInfo> agents, List<String> feedback) { return current().revise(goal, agents, feedback); }
}
