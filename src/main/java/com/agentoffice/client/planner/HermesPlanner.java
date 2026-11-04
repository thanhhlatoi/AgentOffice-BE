package com.agentoffice.client.planner;

import com.agentoffice.config.HermesProperties;
import com.agentoffice.config.LimitsProperties;
import com.agentoffice.domain.task.AgentInfo;
import com.agentoffice.domain.task.Plan;
import com.agentoffice.domain.task.PlannerException;
import com.agentoffice.entity.task.Goal;
import com.agentoffice.service.task.Planner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Giao việc lập kế hoạch cho Hermes: POST /v1/runs rồi đọc SSE GET /v1/runs/{id}/events tới khi run kết thúc.
 * Đồng bộ vì OrchestratorService gọi Planner ngoài transaction, trên pool riêng. Bật bằng agentoffice.planner=hermes.
 * ponytail: run không được lưu DB nên backend restart giữa chừng sẽ không theo dõi lại; cần thì thêm bảng run như bot_tasks.
 */
@Component
@ConditionalOnProperty(name = "agentoffice.planner", havingValue = "hermes")
public class HermesPlanner implements Planner {
    private static final Logger log = LoggerFactory.getLogger(HermesPlanner.class);
    private static final int MAX_RECONNECTS = 5;
    private static final Duration SHORT_TIMEOUT = Duration.ofSeconds(5);
    private static final String END_OF_STREAM = "\0";

    private final String baseUrl;
    private final String apiKey;
    private final String modelCheckUrl;
    private final Duration stallTimeout;
    private final ObjectMapper mapper;
    private final LimitsProperties limits;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(SHORT_TIMEOUT).build();

    /** Trạng thái giữ qua các lần nối lại SSE. */
    private static final class Progress {
        final StringBuilder answer = new StringBuilder();
        String lastEventId;
        boolean received;
    }

    public HermesPlanner(HermesProperties props, ObjectMapper mapper, LimitsProperties limits) {
        if (props.baseUrl().isBlank()) throw new IllegalStateException("agentoffice.planner=hermes cần agentoffice.hermes.base-url (HERMES_URL)");
        this.baseUrl = props.baseUrl().trim().replaceAll("/+$", "");
        this.apiKey = props.apiKey().trim();
        this.modelCheckUrl = props.modelCheckUrl().trim();
        this.stallTimeout = props.stallTimeout();
        this.mapper = mapper;
        this.limits = limits;
    }

    @Override
    public Plan propose(Goal goal, List<AgentInfo> agents) { return revise(goal, agents, List.of()); }

    @Override
    public Plan revise(Goal goal, List<AgentInfo> agents, List<String> feedback) {
        String prompt = PlanPrompt.system(limits) + "\n\n" + PlanPrompt.user(goal, agents, feedback, mapper);
        try {
            checkModel();
            String runId = start(prompt);
            try {
                return PlanPrompt.parse(follow(runId), mapper);
            } catch (InterruptedException e) {
                stop(runId); // không theo dõi lại được sau khi tắt, để run chạy tiếp là tốn vô ích
                throw e;
            }
        } catch (IOException e) {
            throw new PlannerException("Không kết nối được Hermes: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlannerException("Bị ngắt khi chờ Hermes", e);
        }
    }

    private void checkModel() throws IOException, InterruptedException {
        if (modelCheckUrl.isEmpty()) return;
        HttpRequest req = HttpRequest.newBuilder(URI.create(modelCheckUrl)).timeout(SHORT_TIMEOUT).GET().build();
        int code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (code / 100 != 2) throw new PlannerException("Model của Hermes chưa sẵn sàng (HTTP " + code + ")");
    }

    private String start(String prompt) throws IOException, InterruptedException {
        // không thử lại POST: Hermes đã nhận mà mất phản hồi thì sẽ chạy trùng
        HttpResponse<String> r = http.send(request("/v1/runs").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("input", prompt)))).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new PlannerException("Hermes từ chối (HTTP " + r.statusCode() + ")");
        String runId = mapper.readTree(r.body()).path("run_id").asText("");
        if (runId.isEmpty()) throw new PlannerException("Hermes không trả run_id");
        return runId;
    }

    /** Mất luồng giữa chừng thì nối lại vì Hermes vẫn chạy bên kia; chỉ đếm những lần liên tiếp không nhận được sự kiện nào. */
    private String follow(String runId) throws InterruptedException {
        Progress p = new Progress();
        int failures = 0;
        while (true) {
            p.received = false;
            String problem;
            try {
                HttpRequest.Builder req = request("/v1/runs/" + runId + "/events").header("Accept", "text/event-stream");
                if (p.lastEventId != null) req.header("Last-Event-ID", p.lastEventId);
                HttpResponse<InputStream> resp = http.send(req.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                int code = resp.statusCode();
                try (InputStream in = resp.body()) {
                    if (code / 100 == 2) {
                        String output = read(in, runId, p);
                        if (output != null) return output;
                        problem = "Hermes đóng luồng sự kiện trước khi báo kết quả";
                    } else if (code < 500 && code != 429) {
                        throw abandon(runId, "Không đọc được sự kiện Hermes (HTTP " + code + ")"); // 401/403/404: thử lại vô ích
                    } else {
                        problem = "HTTP " + code;
                    }
                }
            } catch (IOException e) {
                problem = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            failures = p.received ? 1 : failures + 1;
            if (failures > MAX_RECONNECTS) throw abandon(runId, "Mất kết nối Hermes sau " + MAX_RECONNECTS + " lần thử lại: " + problem);
            long waitSeconds = Math.min(30, 1L << failures); // 2, 4, 8, 16, 30
            log.warn("Mất luồng sự kiện Hermes của run {} ({}), thử lại lần {} sau {}s", runId, problem, failures, waitSeconds);
            Thread.sleep(waitSeconds * 1000);
        }
    }

    /**
     * Đọc dòng ở thread phụ để bắt được "kẹt": read() của socket không có timeout. Caller đóng `in` thì thread phụ tự thoát.
     * @return kết quả khi run xong; null nếu luồng đóng trước đó.
     */
    private String read(InputStream in, String runId, Progress p) throws InterruptedException {
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread.startVirtualThread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                for (String line; (line = reader.readLine()) != null; ) lines.add(line);
            } catch (IOException e) {
                log.debug("Luồng sự kiện Hermes đóng: {}", e.toString());
            } finally {
                lines.add(END_OF_STREAM);
            }
        });
        long deadline = System.nanoTime() + stallTimeout.toNanos();
        while (true) {
            String line = lines.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (line == null) throw abandon(runId, "Hermes kẹt: " + stallTimeout.toSeconds() + " giây không có sự kiện");
            if (line.equals(END_OF_STREAM)) return null;
            if (line.startsWith("id:")) p.lastEventId = line.substring(3).trim();
            if (!line.startsWith("data:")) continue; // dòng keep-alive không tính là có sự kiện
            JsonNode ev;
            try {
                ev = mapper.readTree(line.substring(5).trim());
            } catch (JsonProcessingException e) {
                continue; // khung không phải JSON
            }
            p.received = true;
            deadline = System.nanoTime() + stallTimeout.toNanos();
            String output = handle(ev, p);
            if (output != null) return output;
        }
    }

    /** @return kết quả khi run.completed, null nếu run còn chạy. */
    private String handle(JsonNode ev, Progress p) {
        String name = ev.path("event").asText("");
        switch (name) {
            case "message.delta" -> p.answer.append(ev.path("delta").asText(""));
            case "tool.failed" -> log.warn("Tool Hermes lỗi: {}", ev.path("tool").asText("tool"));
            case "run.completed" -> { return ev.hasNonNull("output") ? ev.get("output").asText() : p.answer.toString(); }
            case "run.failed" -> throw new PlannerException(ev.path("error").asText("Hermes báo lỗi"));
            case "run.stopped", "run.interrupted", "run.cancelled" -> throw new PlannerException("Run Hermes bị dừng (" + name + ")");
            default -> log.debug("Sự kiện Hermes: {}", name); // tool.started, tool.completed, approval.*...
        }
        return null;
    }

    /** Bỏ run: dừng nó trước, nếu không Orchestrator thử lại sẽ thành 2 run song song. */
    private PlannerException abandon(String runId, String reason) throws InterruptedException {
        stop(runId);
        return new PlannerException(reason);
    }

    private void stop(String runId) throws InterruptedException {
        try {
            http.send(request("/v1/runs/" + runId + "/stop").timeout(SHORT_TIMEOUT).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            log.warn("Không gửi được lệnh stop tới Hermes cho run {}: {}", runId, e.getMessage());
        }
    }

    private HttpRequest.Builder request(String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(stallTimeout);
        if (!apiKey.isEmpty()) b.header("Authorization", "Bearer " + apiKey);
        return b;
    }
}
