package com.agentoffice.service.task;

import com.agentoffice.domain.task.AgentInfo;
import com.agentoffice.domain.task.Plan;
import com.agentoffice.entity.task.Goal;
import java.util.List;

/**
 * Chia goal thành kế hoạch. Planner chỉ ĐỀ XUẤT; backend kiểm tra (PlanValidator) rồi mới tạo task.
 * Chọn triển khai bằng agentoffice.planner = hermes | llm | fake.
 */
public interface Planner {
    Plan propose(Goal goal, List<AgentInfo> agents);

    /** Làm lại có phản hồi (danh sách lỗi của validator hoặc lý do chủ từ chối). Mặc định bỏ qua phản hồi. */
    default Plan revise(Goal goal, List<AgentInfo> agents, List<String> feedback) {
        return propose(goal, agents);
    }
}
