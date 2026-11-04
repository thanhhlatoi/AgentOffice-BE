package com.agentoffice.domain.task;

/** Điểm cắm chạy 1 task bằng 1 agent. Ở BE-1 chỉ có FakeAgentRunner; vòng lặp tool sẽ làm ở BE-10. */
public interface AgentRunner {
    RunResult run(TaskContext ctx);
}
