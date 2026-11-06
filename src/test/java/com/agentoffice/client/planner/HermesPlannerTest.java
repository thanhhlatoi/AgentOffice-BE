package com.agentoffice.client.planner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentoffice.config.HermesProperties;
import com.agentoffice.config.LimitsProperties;
import com.agentoffice.domain.task.AgentInfo;
import com.agentoffice.domain.task.Plan;
import com.agentoffice.domain.task.PlannerException;
import com.agentoffice.entity.task.Goal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Chạy HermesPlanner với một HTTP server giả ngay trong test, đi đúng đường HTTP/SSE thật. */
class HermesPlannerTest {
    private static final UUID AGENT = UUID.randomUUID();
    private static final String PLAN_JSON = "{\"tasks\":[{\"key\":\"a\",\"title\":\"t\",\"input\":\"i\",\"assigneeAgentId\":\"" + AGENT + "\"}]}";
    private static final Goal GOAL = new Goal("Báo cáo tuần", "mô tả", null, null);
    private static final List<AgentInfo> AGENTS = List.of(new AgentInfo(AGENT, "a", "r", "d", "IDLE"));

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private HttpServer server;
    private String postBody, auth;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        on("/v1/runs", ex -> {
            postBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            auth = ex.getRequestHeaders().getFirst("Authorization");
            send(ex, 202, "{\"run_id\":\"r1\"}");
        });
        on("/v1/runs/r1/stop", ex -> send(ex, 200, "{}"));
        server.start();
    }

    @AfterEach void shutdown() {
        release.countDown();
        server.stop(0);
    }

    private void on(String path, HttpHandler handler) {
        server.createContext(path, ex -> {
            calls.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            handler.handle(ex);
        });
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String data(String json) { return "data: " + json + "\n\n"; }

    private String delta(String text) throws IOException {
        return data(mapper.writeValueAsString(java.util.Map.of("event", "message.delta", "delta", text)));
    }

    private HermesPlanner planner(String modelCheckUrl, Duration stall) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return build(new HermesProperties(url + "/", "secret", modelCheckUrl, stall));
    }

    private HermesPlanner build(HermesProperties props) {
        var local = new com.agentoffice.config.LocalLlmProperties("http://x/v1", "", "ollama", "m", java.util.Map.of(), Duration.ofSeconds(5), "");
        var cfg = com.agentoffice.config.RuntimeAiConfig.of(local, props, new com.agentoffice.config.HermesAdminProperties("", "", ""), "fake", "hermes");
        return new HermesPlanner(props, mapper, new LimitsProperties(3, 20), cfg);
    }

    private HermesPlanner planner() { return planner("", Duration.ofSeconds(5)); }

    @Test void readsPlanFromDeltasAndSendsPromptWithKey() throws IOException {
        String events = data("{\"event\":\"tool.started\",\"tool\":\"x\"}") + "data: khong-phai-json\n\n"
                + delta(PLAN_JSON.substring(0, 10)) + delta(PLAN_JSON.substring(10)) + data("{\"event\":\"run.completed\"}");
        on("/v1/runs/r1/events", ex -> send(ex, 200, events));

        Plan plan = planner().revise(GOAL, AGENTS, List.of("thiếu task"));

        assertThat(plan.tasks()).singleElement().satisfies(t -> assertThat(t.assigneeAgentId()).isEqualTo(AGENT));
        assertThat(auth).isEqualTo("Bearer secret");
        assertThat(mapper.readTree(postBody).path("input").asText()).contains("Báo cáo tuần", "thiếu task", AGENT.toString());
        assertThat(calls).doesNotContain("POST /v1/runs/r1/stop");
    }

    @Test void outputOfRunCompletedWins() throws IOException {
        String done = data(mapper.writeValueAsString(java.util.Map.of("event", "run.completed", "output", "Đây: " + PLAN_JSON)));
        on("/v1/runs/r1/events", ex -> send(ex, 200, delta("rác") + done));

        assertThat(planner().propose(GOAL, AGENTS).tasks()).hasSize(1);
    }

    @Test void reconnectsWithLastEventIdAfterStreamDrops() {
        AtomicInteger connections = new AtomicInteger();
        List<String> lastIds = new CopyOnWriteArrayList<>();
        on("/v1/runs/r1/events", ex -> {
            lastIds.add(String.valueOf(ex.getRequestHeaders().getFirst("Last-Event-ID")));
            send(ex, 200, connections.getAndIncrement() == 0 ? "id: 7\n" + data("{\"event\":\"tool.started\"}")
                    : data("{\"event\":\"run.completed\",\"output\":" + quoted(PLAN_JSON) + "}"));
        });

        assertThat(planner().propose(GOAL, AGENTS).tasks()).hasSize(1);
        assertThat(lastIds).containsExactly("null", "7");
    }

    private String quoted(String s) {
        try { return mapper.writeValueAsString(s); } catch (IOException e) { throw new IllegalStateException(e); }
    }

    @Test void stalledRunIsStopped() {
        on("/v1/runs/r1/events", ex -> {
            ex.sendResponseHeaders(200, 0);
            ex.getResponseBody().write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
            ex.getResponseBody().flush();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            ex.close();
        });

        assertThatThrownBy(() -> planner("", Duration.ofMillis(300)).propose(GOAL, AGENTS))
                .isInstanceOf(PlannerException.class).hasMessageContaining("kẹt");
        assertThat(calls).contains("POST /v1/runs/r1/stop");
    }

    @Test void runFailedSurfacesHermesErrorWithoutStop() {
        on("/v1/runs/r1/events", ex -> send(ex, 200, data("{\"event\":\"run.failed\",\"error\":\"hết quota\"}")));

        assertThatThrownBy(() -> planner().propose(GOAL, AGENTS)).isInstanceOf(PlannerException.class).hasMessage("hết quota");
        assertThat(calls).doesNotContain("POST /v1/runs/r1/stop");
    }

    @Test void unauthorizedEventsAreNotRetried() {
        on("/v1/runs/r1/events", ex -> send(ex, 401, ""));

        assertThatThrownBy(() -> planner().propose(GOAL, AGENTS)).isInstanceOf(PlannerException.class).hasMessageContaining("401");
        assertThat(calls).containsOnlyOnce("GET /v1/runs/r1/events").contains("POST /v1/runs/r1/stop");
    }

    @Test void modelDownBlocksTheRun() {
        on("/api/tags", ex -> send(ex, 503, ""));
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/tags";

        assertThatThrownBy(() -> planner(url, Duration.ofSeconds(5)).propose(GOAL, AGENTS)).isInstanceOf(PlannerException.class);
        assertThat(calls).containsExactly("GET /api/tags");
    }

    @Test void missingBaseUrlFailsWhenPlanning() {
        // địa chỉ đọc mỗi lần gọi (đổi được trên giao diện) nên thiếu địa chỉ báo lỗi lúc lập kế hoạch, không còn chặn khởi động
        assertThatThrownBy(() -> build(new HermesProperties(" ", "", "", Duration.ofSeconds(1))).propose(GOAL, AGENTS))
                .isInstanceOf(PlannerException.class).hasMessageContaining("Hermes");
    }
}
