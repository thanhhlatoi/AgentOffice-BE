package com.agentoffice.client.llm;

import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Hỏi thẳng máy Ollama về tình trạng và danh sách model (/api/version, /api/tags, /api/show) cho màn Cài đặt AI.
 * KHÔNG phải LlmClient: chỉ kiểm tra/ thử kết nối, không phải lời gọi AI của nghiệp vụ nên không ghi usage.
 * Chỉ nói chuyện với đúng địa chỉ đã cấu hình (agentoffice.llm.local.base-url), không nhận địa chỉ từ client (chặn SSRF).
 */
@Component
public class OllamaClient {
    private static final Duration SHORT = Duration.ofSeconds(5);

    private final com.agentoffice.config.RuntimeAiConfig cfg;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public OllamaClient(com.agentoffice.config.RuntimeAiConfig cfg, ObjectMapper mapper) {
        this.cfg = cfg;
        this.mapper = mapper;
    }

    /** Địa chỉ gốc của Ollama: base-url của OpenAI-compat bỏ đuôi /v1 (vd http://192.168.1.184:11434). */
    public String root() {
        String u = cfg.get().ollamaBaseUrl().trim().replaceAll("/+$", "");
        return u.endsWith("/v1") ? u.substring(0, u.length() - 3) : u;
    }

    public JsonNode version() { return get("/api/version"); }

    public JsonNode tags() { return get("/api/tags"); }

    /** Khả năng của model (completion, tools, thinking...) và chi tiết. */
    public JsonNode show(String model) {
        ObjectNode body = mapper.createObjectNode().put("model", model);
        return send(HttpRequest.newBuilder(URI.create(root() + "/api/show")).timeout(SHORT)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
    }

    /** Model nào đang nằm trong RAM/VRAM. */
    public JsonNode running() { return get("/api/ps"); }

    /**
     * Gửi một câu ngắn qua /v1/chat/completions để thử model chạy được thật (kể cả nạp model lần đầu, nên timeout dài).
     * Dùng đúng cấu hình reasoning_effort của hệ thống để kết quả giống lúc chạy thật.
     */
    public String chatOnce(String model, String prompt) {
        ObjectNode body = mapper.createObjectNode().put("model", model).put("stream", false).put("max_tokens", 64);
        body.putArray("messages").addObject().put("role", "user").put("content", prompt);
        var c = cfg.get();
        if (!c.reasoningEffort().isBlank()) body.put("reasoning_effort", c.reasoningEffort().trim());
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(c.chatBaseUrl().trim().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(Duration.ofSeconds(90)).header("Content-Type", "application/json");
        if (!c.chatApiKey().isBlank()) b.header("Authorization", "Bearer " + c.chatApiKey().trim());
        JsonNode out = send(b.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build());
        String text = out.path("choices").path(0).path("message").path("content").asText("").trim();
        if (text.isEmpty()) {
            throw new AppException(ErrorCode.LLM_UNAVAILABLE, "Model không trả nội dung"
                    + (out.path("choices").path(0).path("message").hasNonNull("reasoning") ? " (dùng hết token để suy luận: đặt LOCAL_LLM_REASONING_EFFORT=none)" : ""));
        }
        return text;
    }

    private JsonNode get(String path) {
        return send(HttpRequest.newBuilder(URI.create(root() + path)).timeout(SHORT).GET().build());
    }

    private JsonNode send(HttpRequest req) {
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new AppException(ErrorCode.LLM_UNAVAILABLE, "Ollama trả HTTP " + r.statusCode());
            }
            return mapper.readTree(r.body());
        } catch (IOException e) {
            throw new AppException(ErrorCode.LLM_UNAVAILABLE, "Không kết nối được Ollama tại " + root(), null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(ErrorCode.LLM_UNAVAILABLE, "Bị ngắt khi gọi Ollama", null, e);
        }
    }
}
