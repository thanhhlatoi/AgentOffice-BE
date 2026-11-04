package com.agentoffice.client.llm;

import com.agentoffice.config.LocalLlmProperties;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Máy AI local qua API chuẩn OpenAI: POST {baseUrl}/chat/completions (không stream). Ollama, LM Studio, vLLM...
 * Bật bằng agentoffice.llm.provider=local. Mọi lỗi mạng/HTTP thành AppException(LLM_UNAVAILABLE) để MeteredLlmClient ghi usage lỗi.
 */
@Component
@Qualifier("raw")
@ConditionalOnProperty(name = "agentoffice.llm.provider", havingValue = "local")
public class OpenAiCompatLlmClient implements LlmClient {
    /** Model suy luận (qwen3, deepseek-r1...) có thể nhét phần nghĩ vào content: bỏ đi, chỉ giữ câu trả lời. */
    private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>\\s*");

    private final LocalLlmProperties props;
    private final String baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public OpenAiCompatLlmClient(LocalLlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.baseUrl = props.baseUrl().trim().replaceAll("/+$", "");
        this.mapper = mapper;
    }

    @Override
    public String provider() { return props.providerName(); }

    @Override
    public LlmResponse complete(LlmRequest req) {
        String model = resolveModel(req.model());
        try {
            ObjectNode body = mapper.createObjectNode().put("model", model).put("stream", false).put("max_tokens", req.maxOutputTokens());
            if (!props.reasoningEffort().isBlank()) body.put("reasoning_effort", props.reasoningEffort().trim());
            ArrayNode msgs = body.putArray("messages");
            for (LlmRequest.Msg m : req.messages()) msgs.addObject().put("role", m.role()).put("content", m.content());

            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions")).timeout(props.timeout())
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (!props.apiKey().isBlank()) b.header("Authorization", "Bearer " + props.apiKey().trim());

            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw unavailable("Máy AI local từ chối (HTTP " + r.statusCode() + ")", null);

            JsonNode json = mapper.readTree(r.body());
            String text = THINK.matcher(json.path("choices").path(0).path("message").path("content").asText("")).replaceAll("").trim();
            if (text.isEmpty()) {
                boolean thinking = !json.path("choices").path(0).path("message").path("reasoning").asText("").isEmpty();
                throw unavailable("Máy AI local không trả nội dung nào (model " + model + ")" + (thinking
                        ? ": model dùng hết token để suy luận, đặt LOCAL_LLM_REASONING_EFFORT=none hoặc tăng maxOutputTokens" : ""), null);
            }
            JsonNode usage = json.path("usage");
            return new LlmResponse(text, usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0),
                    json.path("model").asText(model));
        } catch (IOException e) {
            throw unavailable("Không kết nối được máy AI local " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("Bị ngắt khi chờ máy AI local", e);
        } catch (IllegalArgumentException e) {
            throw unavailable("agentoffice.llm.local.base-url không hợp lệ: " + baseUrl, e);
        }
    }

    /** Alias (profile) -> model thật; tên đã là model thật (có ':' hoặc '/', vd qwen3:4b) giữ nguyên; còn lại dùng model mặc định. */
    String resolveModel(String requested) {
        String alias = props.modelAliases().get(requested);
        if (alias != null && !alias.isBlank()) return alias;
        if (requested != null && (requested.contains(":") || requested.contains("/"))) return requested;
        return props.defaultModel();
    }

    private static AppException unavailable(String msg, Throwable cause) {
        return new AppException(ErrorCode.LLM_UNAVAILABLE, msg, null, cause);
    }
}
