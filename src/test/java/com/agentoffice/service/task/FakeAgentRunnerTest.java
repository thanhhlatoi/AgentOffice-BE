package com.agentoffice.service.task;

import com.agentoffice.domain.task.RunResult;
import com.agentoffice.domain.task.TaskContext;
import static org.assertj.core.api.Assertions.assertThat;

import com.agentoffice.client.llm.FakeLlmClient;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentoffice.service.llm.MeteredLlmClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class FakeAgentRunnerTest {
    private final FakeLlmClient fake = new FakeLlmClient();
    private final MeteredLlmClient metered = mock(MeteredLlmClient.class);
    private final FakeAgentRunner runner = new FakeAgentRunner(metered, 0);

    @BeforeEach void delegateToFake() { when(metered.complete(any())).thenAnswer(i -> fake.complete(i.getArgument(0))); }

    private TaskContext ctx(String input, int attempts, AtomicBoolean cancel) {
        return new TaskContext(UUID.randomUUID(), UUID.randomUUID(), null, null, "tiêu đề", input, attempts, 0, cancel::get);
    }

    @Test void plainTaskSucceedsAndReportsTokens() {
        RunResult r = runner.run(ctx("hello", 0, new AtomicBoolean()));
        assertThat(r.outcome()).isEqualTo(RunResult.Outcome.SUCCEEDED);
        assertThat(r.output()).startsWith("[fake]");
        assertThat(r.tokensUsed()).isPositive();
    }

    @Test void failDirective() {
        assertThat(runner.run(ctx("[fail] x", 0, new AtomicBoolean())).outcome()).isEqualTo(RunResult.Outcome.FAILED);
    }

    @Test void flakyDirectiveRecoversAfterNAttempts() {
        assertThat(runner.run(ctx("[flaky:2] x", 1, new AtomicBoolean())).outcome()).isEqualTo(RunResult.Outcome.RETRYABLE_FAILURE);
        assertThat(runner.run(ctx("[flaky:2] x", 2, new AtomicBoolean())).outcome()).isEqualTo(RunResult.Outcome.SUCCEEDED);
    }

    @Test void slowTaskStopsWhenCancelRequested() {
        AtomicBoolean cancel = new AtomicBoolean(true);
        assertThat(runner.run(ctx("[slow:5000] x", 0, cancel)).outcome()).isEqualTo(RunResult.Outcome.CANCELLED);
    }
}
