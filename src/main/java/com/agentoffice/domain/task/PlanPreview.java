package com.agentoffice.domain.task;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Bản đọc được (tiếng Việt) của kế hoạch cho người duyệt. */
public final class PlanPreview {
    private PlanPreview() {}

    public static String render(String goalTitle, Plan plan, List<AgentInfo> agents) {
        Map<UUID, String> names = agents.stream().collect(Collectors.toMap(AgentInfo::id, AgentInfo::name, (a, b) -> a));
        StringBuilder sb = new StringBuilder("Kế hoạch cho «").append(goalTitle).append("» gồm ").append(plan.tasks().size()).append(" việc:\n");
        int i = 1;
        for (Plan.SubTask t : plan.tasks()) {
            sb.append(i++).append(". [").append(t.key()).append("] ").append(t.title()).append(" → ")
              .append(names.getOrDefault(t.assigneeAgentId(), "?"));
            if (!t.dependsOnKeys().isEmpty()) sb.append(" (sau: ").append(String.join(", ", t.dependsOnKeys())).append(")");
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
