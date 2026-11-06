package com.agentoffice.client.llm;

import com.agentoffice.config.LlmProperties;
import com.agentoffice.domain.usage.LlmCallException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Gọi gateway OpenAI-compatible (9Router): POST {base-url}/v1/chat/completions. req.model() là TÊN COMBO trên dashboard 9Router.
 * Không thử lại ở đây: việc thử lại thuộc dispatcher (RETRYABLE_FAILURE), thử lại ở hai tầng sẽ nhân đôi tiền token.
 * Log chỉ có model, mã HTTP, thời gian, số token: không log api-key, prompt, câu trả lời.
 */
@Component
@Qualifier("raw")
@ConditionalOnProperty(name = "agentoffice.llm.provider", havingValue = "openai-compatible")
public class OpenAiCompatibleLlmClient implements LlmClient {
    /** Khớp model_price.provider: bản ghi giá của combo phải khai với provider này (PUT /api/model-prices). */
    static final String PROVIDER = "9router";
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final URI endpoint;
    private final URI modelsEndpoint;
    private final String apiKey;
    private final Duration readTimeout;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OpenAiCompatibleLlmClient(LlmProperties props, ObjectMapper mapper) {
        LlmProperties.Gateway g = props.gateway();
        if (g.baseUrl().isBlank())
            throw new IllegalStateException("agentoffice.llm.provider=openai-compatible cần agentoffice.llm.gateway.base-url (NINEROUTER_URL)");
        String base = g.baseUrl().trim().replaceAll("/+$", "");
        this.endpoint = URI.create(base + "/v1/chat/completions");
        this.modelsEndpoint = URI.create(base + "/v1/models");
        this.apiKey = g.apiKey().trim();
        this.readTimeout = g.readTimeout();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(g.connectTimeout()).build();
    }

    @Override public String provider() { return PROVIDER; }

    @Override
    public LlmResponse complete(LlmRequest req) {
        long start = System.nanoTime();
        try {
            HttpResponse<String> r = http.send(request(req), HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (r.statusCode() / 100 != 2) {
                log.warn("LLM gateway model={} http={} {}ms", req.model(), r.statusCode(), ms);
                throw LlmErrorMapper.fromStatus(r.statusCode());
            }
            LlmResponse out = parse(req, r.body());
            log.info("LLM gateway model={} http={} {}ms in={} out={}", req.model(), r.statusCode(), ms, out.inputTokens(), out.outputTokens());
            return out;
        } catch (IOException e) {
            log.warn("LLM gateway model={} lỗi mạng {}ms: {}", req.model(), (System.nanoTime() - start) / 1_000_000, e.getClass().getSimpleName());
            throw LlmErrorMapper.fromTransport(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmCallException("Bị ngắt khi chờ LLM gateway", false, e);
        }
    }

    /** GET {base-url}/v1/models. Lỗi HTTP/mạng đi qua LlmErrorMapper như lúc gọi chat. */
    @Override
    public List<LlmModel> models() {
        HttpRequest.Builder b = HttpRequest.newBuilder(modelsEndpoint).timeout(readTimeout).GET();
        if (!apiKey.isEmpty()) b.header("Authorization", "Bearer " + apiKey);
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                log.warn("LLM gateway liệt kê model http={}", r.statusCode());
                throw LlmErrorMapper.fromStatus(r.statusCode());
            }
            List<LlmModel> out = new java.util.ArrayList<>();
            for (JsonNode m : mapper.readTree(r.body()).path("data"))
                if (m.path("id").isTextual()) out.add(model(m));
            return out;
        } catch (IOException e) {
            throw LlmErrorMapper.fromTransport(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmCallException("Bị ngắt khi chờ LLM gateway", false, e);
        }
    }

    private static LlmModel model(JsonNode m) {
        JsonNode c = m.path("capabilities");
        return new LlmModel(m.get("id").asText(), m.path("owned_by").asText(null), longOrNull(c.get("contextWindow")), longOrNull(c.get("maxOutput")),
                boolOrNull(c.get("vision")), boolOrNull(c.get("tools")), boolOrNull(c.get("reasoning")));
    }

    private static Long longOrNull(JsonNode n) { return n != null && n.isIntegralNumber() ? n.asLong() : null; }

    private static Boolean boolOrNull(JsonNode n) { return n != null && n.isBoolean() ? n.asBoolean() : null; }

    private HttpRequest request(LlmRequest req) throws IOException {
        List<Map<String, String>> messages = req.messages().stream().map(m -> Map.of("role", m.role(), "content", m.content())).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model());
        body.put("messages", messages);
        body.put("max_tokens", req.maxOutputTokens());
        body.put("stream", false);
        HttpRequest.Builder b = HttpRequest.newBuilder(endpoint).timeout(readTimeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (!apiKey.isEmpty()) b.header("Authorization", "Bearer " + apiKey);
        return b.build();
    }

    private LlmResponse parse(LlmRequest req, String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException e) {
            throw new LlmCallException("LLM gateway trả nội dung không phải JSON", false, e);
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (!content.isTextual()) throw new LlmCallException("LLM gateway trả JSON thiếu choices[0].message.content", false);
        JsonNode usage = root.path("usage");
        if (!usage.hasNonNull("prompt_tokens") || !usage.hasNonNull("completion_tokens")) {
            log.warn("LLM gateway model={} không trả usage; ghi 0 để MeteredLlmClient ước lượng", req.model());
            return new LlmResponse(content.asText(), 0, 0, root.path("model").asText(req.model()));
        }
        return new LlmResponse(content.asText(), usage.path("prompt_tokens").asLong(), usage.path("completion_tokens").asLong(),
                root.path("model").asText(req.model()), usage.path("prompt_tokens_details").path("cached_tokens").asLong(0));
    }
}
