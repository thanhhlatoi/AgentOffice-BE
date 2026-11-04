package com.agentoffice.domain.task;

/** Planner không trả được kế hoạch dùng được (lỗi gọi, JSON hỏng...). Orchestrator coi như một lần thất bại và làm lại. */
public class PlannerException extends RuntimeException {
    public PlannerException(String message) { super(message); }
    public PlannerException(String message, Throwable cause) { super(message, cause); }
}
