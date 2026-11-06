package com.agentoffice.domain.task;

/** Điểm cắm chạy 1 task bằng 1 agent. FakeAgentRunner (mặc định) hoặc HermesAgentRunner, chọn bằng agentoffice.runner.type; vòng lặp tool sẽ làm ở BE-10. */
public interface AgentRunner {
    RunResult run(TaskContext ctx);
}
