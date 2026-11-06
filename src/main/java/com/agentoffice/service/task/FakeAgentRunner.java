package com.agentoffice.service.task;

import com.agentoffice.domain.task.AgentRunner;
import com.agentoffice.domain.task.RunResult;
import com.agentoffice.domain.task.TaskContext;
import com.agentoffice.domain.usage.CallContext;
import com.agentoffice.service.llm.MeteredLlmClient;
import com.agentoffice.client.llm.LlmRequest;
import com.agentoffice.client.llm.LlmResponse;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runner giả: ngủ ngắn rồi gọi MeteredLlmClient. Chỉ để chứng minh vòng đời. Chỉ thị đặc biệt ở đầu input (cho test/dev):
 *   [fail]          -> thất bại vĩnh viễn
 *   [flaky:N]       -> lỗi tạm thời cho tới khi attempts >= N
 *   [slow:ms]       -> chạy chậm ms mili-giây (kiểm tra cờ huỷ mỗi 10ms)
 */
@Component
@ConditionalOnProperty(name = "agentoffice.runner.type", havingValue = "fake", matchIfMissing = true)
public class FakeAgentRunner implements AgentRunner {
    private final MeteredLlmClient llm;
    private final long delayMs;

    public FakeAgentRunner(MeteredLlmClient llm, @Value("${agentoffice.runner.fake-delay-ms:30}") long delayMs) {
        this.llm = llm;
        this.delayMs = delayMs;
    }

    @Override
    public RunResult run(TaskContext ctx) {
        String in = ctx.input() == null ? "" : ctx.input();
        long sleep = delayMs;
        if (in.startsWith("[fail]")) return RunResult.failed("fake failure");
        if (in.startsWith("[flaky:")) {
            int n = Integer.parseInt(in.substring(7, in.indexOf(']')));
            if (ctx.attempts() < n) return RunResult.retryable("fake transient error (attempt " + ctx.attempts() + ")");
        }
        if (in.startsWith("[slow:")) sleep = Long.parseLong(in.substring(6, in.indexOf(']')));
        try {
            for (long slept = 0; slept < sleep; slept += 10) {
                if (ctx.cancelRequested().getAsBoolean()) return RunResult.cancelled();
                Thread.sleep(Math.min(10, sleep - slept));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RunResult.retryable("interrupted");
        }
        if (ctx.cancelRequested().getAsBoolean()) return RunResult.cancelled();
        LlmResponse r = llm.complete(new LlmRequest(ctx.agentModelProfile() == null ? "fake" : ctx.agentModelProfile(),
                List.of(new LlmRequest.Msg("user", ctx.title())), 256,
                new CallContext(ctx.agentId(), ctx.taskId(), ctx.goalId(), null, null, CallContext.Purpose.AGENT_RUN, null)));
        return RunResult.succeeded(r.text(), r.inputTokens() + r.outputTokens());
    }
}
