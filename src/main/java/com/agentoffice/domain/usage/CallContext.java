package com.agentoffice.domain.usage;

import java.util.UUID;

/**
 * Ngữ cảnh của MỘT cuộc gọi LLM, truyền tường minh trong LlmRequest (không ThreadLocal: quên truyền = lỗi biên dịch).
 * agentId null chỉ hợp lệ với PLAN/MODEL_TEST (Planner chưa gắn với agent nào).
 * requestId null = MeteredLlmClient tự sinh; truyền vào để thử lại an toàn (cùng requestId không ghi usage lần hai).
 */
public record CallContext(UUID agentId, UUID taskId, UUID goalId, UUID projectId, UUID departmentId,
                          Purpose purpose, UUID requestId) {
    public enum Purpose { AGENT_RUN, PLAN, EMBEDDING, MODEL_TEST }

    public CallContext {
        if (purpose == null) throw new IllegalArgumentException("purpose bắt buộc");
    }

    public static CallContext forPlanner(UUID goalId) {
        return new CallContext(null, null, goalId, null, null, Purpose.PLAN, null);
    }
}
