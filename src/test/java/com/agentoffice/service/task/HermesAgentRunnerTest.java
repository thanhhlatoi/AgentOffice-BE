package com.agentoffice.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentoffice.client.hermes.HermesBotClient;
import com.agentoffice.domain.task.RunResult;
import com.agentoffice.domain.task.RunResult.Outcome;
import com.agentoffice.domain.task.TaskContext;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.repository.agent.AgentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HermesAgentRunnerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final UUID taskId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final String runId = taskId + "-2";
    private final HermesBotClient hermes = mock(HermesBotClient.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final HermesAgentRunner runner = new HermesAgentRunner(hermes, agents, 1, 60);

    @BeforeEach
    void setUp() {
        Agent a = new Agent("Bot", "Vai trò", "Phòng");
        a.setHermesProfile("bot-1");
        when(agents.findById(agentId)).thenReturn(Optional.of(a));
        when(hermes.configured()).thenReturn(true);
    }

    private TaskContext ctx(BooleanSupplier cancel) {
        return new TaskContext(taskId, agentId, null, null, "Tiêu đề", " nội dung ", 2, 0, cancel);
    }

    private static JsonNode run(String json) throws Exception { return JSON.readTree(json); }

    @Test void giaoDungBotRoiTraKetQua() throws Exception {
        when(hermes.run(runId)).thenReturn(run("{\"status\":\"running\"}"), run("{\"status\":\"succeeded\",\"output\":\"xong\",\"tokens\":42}"));
        RunResult r = runner.run(ctx(() -> false));
        verify(hermes).startRun("bot-1", runId, "Tiêu đề\n\nnội dung");
        assertThat(r.outcome()).isEqualTo(Outcome.SUCCEEDED);
        assertThat(r.output()).isEqualTo("xong");
        assertThat(r.tokensUsed()).isEqualTo(42);
    }

    @Test void agentChuaCoBotThiKhongGoiHermes() {
        when(agents.findById(agentId)).thenReturn(Optional.of(new Agent("Bot", "Vai trò", "Phòng")));
        assertThat(runner.run(ctx(() -> false)).outcome()).isEqualTo(Outcome.FAILED);
        verify(hermes, never()).startRun(anyString(), anyString(), anyString());
    }

    @Test void botMatTrenHermesLaThatBaiHan() {
        when(hermes.startRun(anyString(), anyString(), anyString())).thenThrow(new NotFoundException("bot Hermes", "x"));
        assertThat(runner.run(ctx(() -> false)).outcome()).isEqualTo(Outcome.FAILED);
    }

    @Test void hermesMatKetNoiLucGiaoViecThiThuLai() {
        when(hermes.startRun(anyString(), anyString(), anyString())).thenThrow(new AppException(ErrorCode.HERMES_UNAVAILABLE, "mất mạng"));
        assertThat(runner.run(ctx(() -> false)).outcome()).isEqualTo(Outcome.RETRYABLE_FAILURE);
    }

    @Test void huyGiuaChungThiDungRunTrenHermes() throws Exception {
        when(hermes.run(runId)).thenReturn(run("{\"status\":\"running\"}"));
        boolean[] cancel = {false};
        when(hermes.startRun(anyString(), anyString(), anyString())).thenAnswer(i -> { cancel[0] = true; return null; });
        assertThat(runner.run(ctx(() -> cancel[0])).outcome()).isEqualTo(Outcome.CANCELLED);
        verify(hermes).stopRun(eq(runId));
    }

    @Test void runLoiThiThuLaiConQuaGioThiThatBai() throws Exception {
        when(hermes.run(any())).thenReturn(run("{\"status\":\"failed\",\"error\":\"ollama\"}"));
        RunResult failed = runner.run(ctx(() -> false));
        assertThat(failed.outcome()).isEqualTo(Outcome.RETRYABLE_FAILURE);
        assertThat(failed.error()).isEqualTo("ollama");
        when(hermes.run(any())).thenReturn(run("{\"status\":\"timeout\",\"error\":\"qua gio\"}"));
        assertThat(runner.run(ctx(() -> false)).outcome()).isEqualTo(Outcome.FAILED);
    }

    @Test void hermesKhoiDongLaiLamMatRunThiThuLai() {
        when(hermes.run(any())).thenThrow(new NotFoundException("bot Hermes", "x"));
        assertThat(runner.run(ctx(() -> false)).outcome()).isEqualTo(Outcome.RETRYABLE_FAILURE);
    }

    @Test void loiMangTamThoiKhongLamHongRun() throws Exception {
        when(hermes.run(any())).thenThrow(new AppException(ErrorCode.HERMES_UNAVAILABLE, "mất mạng"))
                .thenReturn(run("{\"status\":\"succeeded\",\"output\":\"ok\"}"));
        assertThat(runner.run(ctx(() -> false)).outcome()).isEqualTo(Outcome.SUCCEEDED);
    }

    @Test void promptGhepTieuDeVaNoiDung() {
        assertThat(HermesAgentRunner.prompt("T", null)).isEqualTo("T");
        assertThat(HermesAgentRunner.prompt("T", "  ")).isEqualTo("T");
        assertThat(HermesAgentRunner.prompt("T", " x ")).isEqualTo("T\n\nx");
    }
}
