package com.agentoffice.client.planner;

import com.agentoffice.domain.task.AgentInfo;
import com.agentoffice.domain.task.Plan;
import com.agentoffice.service.task.Planner;
import com.agentoffice.config.LimitsProperties;
import com.agentoffice.domain.usage.CallContext;
import com.agentoffice.service.llm.MeteredLlmClient;
import com.agentoffice.client.llm.LlmRequest;
import com.agentoffice.client.llm.LlmRequest.Msg;
import com.agentoffice.entity.task.Goal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

/** Planner dự phòng: nhờ LLM trả JSON kế hoạch. Đầu ra LLM LUÔN được PlanValidator kiểm trước khi dùng. */
@Component
public class LlmPlanner implements Planner {
    private final MeteredLlmClient llm;
    private final ObjectMapper mapper;
    private final LimitsProperties limits;
    private final com.agentoffice.config.RuntimeAiConfig cfg;

    public LlmPlanner(MeteredLlmClient llm, ObjectMapper mapper, LimitsProperties limits, com.agentoffice.config.RuntimeAiConfig cfg) {
        this.cfg = cfg;
        this.llm = llm;
        this.mapper = mapper;
        this.limits = limits;
    }

    @Override
    public Plan propose(Goal goal, List<AgentInfo> agents) { return revise(goal, agents, List.of()); }

    @Override
    public Plan revise(Goal goal, List<AgentInfo> agents, List<String> feedback) {
        List<Msg> msgs = List.of(new Msg("system", PlanPrompt.system(limits)),
                new Msg("user", PlanPrompt.user(goal, agents, feedback, mapper)));
        String text = llm.complete(new LlmRequest("planner", msgs, cfg.get().maxOutputTokens(), CallContext.forPlanner(goal.getId()))).text();
        return PlanPrompt.parse(text, mapper);
    }
}
