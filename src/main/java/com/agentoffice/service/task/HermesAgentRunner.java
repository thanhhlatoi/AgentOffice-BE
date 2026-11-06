package com.agentoffice.service.task;

import com.agentoffice.client.hermes.HermesBotClient;
import com.agentoffice.domain.task.AgentRunner;
import com.agentoffice.domain.task.RunResult;
import com.agentoffice.domain.task.TaskContext;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.repository.agent.AgentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Chạy task bằng đúng bot Hermes của agent (agent.hermes_profile), nên model, SOUL, skill, plugin của bot có tác dụng thật.
 * hermes_admin.py chạy một lượt `hermes -p <bot> --oneshot` rồi trả kết quả; runner hỏi trạng thái định kỳ.
 * Bật bằng agentoffice.runner.type=hermes.
 * Chế độ này bỏ qua phê duyệt của Hermes, nên hermes_admin.py chỉ cấp toolset không đụng tới máy (xem HERMES_RUN_TOOLSETS);
 * tool có rủi ro phải đi qua ToolGateway của BE, chưa nối vào runner này.
 * ponytail: chi phí không qua MeteredLlmClient nên chưa bị BudgetGuard chặn; số token chỉ cộng vào goal.
 */
@Component
@ConditionalOnProperty(name = "agentoffice.runner.type", havingValue = "hermes")
public class HermesAgentRunner implements AgentRunner {
    private static final Logger log = LoggerFactory.getLogger(HermesAgentRunner.class);
    private static final int MAX_POLL_FAILURES = 5;

    private final HermesBotClient hermes;
    private final AgentRepository agents;
    private final long pollMs;
    private final Duration maxWait;

    public HermesAgentRunner(HermesBotClient hermes, AgentRepository agents,
                             @Value("${agentoffice.runner.poll-ms:2000}") long pollMs,
                             @Value("${agentoffice.runner.max-wait-seconds:7200}") long maxWaitSeconds) {
        this.hermes = hermes;
        this.agents = agents;
        this.pollMs = pollMs;
        this.maxWait = Duration.ofSeconds(maxWaitSeconds);
    }

    @Override
    public RunResult run(TaskContext ctx) {
        if (!hermes.configured()) return RunResult.failed("Chưa cấu hình HERMES_ADMIN_URL / HERMES_ADMIN_KEY");
        String bot = agents.findById(ctx.agentId()).map(Agent::getHermesProfile).orElse(null);
        if (bot == null) return RunResult.failed("Agent chưa có bot trên Hermes, hãy đồng bộ trước");
        if (ctx.cancelRequested().getAsBoolean()) return RunResult.cancelled();
        // Cùng lượt thử thì cùng runId: BE restart giữa chừng sẽ theo dõi tiếp run cũ, không chạy trùng
        String runId = ctx.taskId() + "-" + ctx.attempts();
        try {
            hermes.startRun(bot, runId, prompt(ctx.title(), ctx.input()));
        } catch (NotFoundException e) {
            return RunResult.failed("Bot '" + bot + "' không còn trên Hermes");
        } catch (AppException e) {
            if (e.code() == ErrorCode.VALIDATION) return RunResult.failed(e.getMessage());
            return RunResult.retryable(e.getMessage());
        }
        return follow(runId, ctx);
    }

    private RunResult follow(String runId, TaskContext ctx) {
        long deadline = System.nanoTime() + maxWait.toNanos();
        int failures = 0;
        while (true) {
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stop(runId);
                return RunResult.retryable("interrupted");
            }
            if (ctx.cancelRequested().getAsBoolean()) {
                stop(runId);
                return RunResult.cancelled();
            }
            if (System.nanoTime() > deadline) {
                stop(runId);
                return RunResult.retryable("Quá " + maxWait.toSeconds() + " giây chưa có kết quả từ Hermes");
            }
            JsonNode run;
            try {
                run = hermes.run(runId);
                failures = 0;
            } catch (NotFoundException e) {
                return RunResult.retryable("Máy Hermes không còn run này (dịch vụ vừa khởi động lại)");
            } catch (AppException e) {
                if (++failures >= MAX_POLL_FAILURES) return RunResult.retryable(e.getMessage());
                continue;
            }
            RunResult done = result(run);
            if (done != null) return done;
        }
    }

    /** @return null nếu run còn chạy. */
    private static RunResult result(JsonNode run) {
        String error = run.path("error").asText("Hermes báo lỗi");
        return switch (run.path("status").asText("")) {
            case "succeeded" -> RunResult.succeeded(run.path("output").asText(""), run.path("tokens").asLong(0));
            case "failed" -> RunResult.retryable(error);   // thường do model/Ollama tạm thời
            case "timeout" -> RunResult.failed(error);     // chạy lại sẽ lại tốn nguyên khoảng đó
            case "cancelled" -> RunResult.cancelled();
            default -> null;
        };
    }

    private void stop(String runId) {
        try {
            hermes.stopRun(runId);
        } catch (AppException e) {
            log.warn("Không dừng được run {} trên Hermes: {}", runId, e.getMessage());
        }
    }

    static String prompt(String title, String input) {
        String in = input == null ? "" : input.strip();
        return in.isEmpty() ? title : title + "\n\n" + in;
    }
}
