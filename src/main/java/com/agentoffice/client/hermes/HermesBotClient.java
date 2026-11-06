package com.agentoffice.client.hermes;

import com.agentoffice.config.HermesAdminProperties;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.exception.NotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Đọc / tạo / sửa các "con bot" (profile) của Hermes qua hermes_admin.py chạy trên máy Hermes.
 * BE không đọc thẳng thư mục ~/.hermes vì Hermes nằm ở máy khác. Không trả giá trị .env về (chỉ có tên biến).
 */
@Component
public class HermesBotClient {
    private static final Logger log = LoggerFactory.getLogger(HermesBotClient.class);

    private final String baseUrl;
    private final com.agentoffice.config.RuntimeAiConfig cfg;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public HermesBotClient(HermesAdminProperties props, ObjectMapper mapper, com.agentoffice.config.RuntimeAiConfig cfg) {
        this.cfg = cfg;
        this.baseUrl = props.url().trim().replaceAll("/+$", "");
        this.mapper = mapper;
    }

    /** Khoá admin đọc mỗi lần gọi: đổi "token admin" trên giao diện có hiệu lực ngay. */
    private String key() { return cfg.get().hermesAdminKey(); }

    public boolean configured() { return !baseUrl.isEmpty() && !key().isEmpty(); }

    public JsonNode list() { return call("GET", "/profiles", null); }

    public JsonNode get(String name) { return call("GET", "/profiles/" + name, null); }

    public JsonNode update(String name, JsonNode body) {
        JsonNode result = call("PUT", "/profiles/" + name, body);
        log.info("Sửa bot Hermes '{}': {}", name, result.path("changed"));
        return result;
    }

    /** Tạo profile mới. Kiểm tra bot nguồn trước để 404 sau đó chỉ có nghĩa là hermes_admin.py chưa hỗ trợ. */
    public JsonNode create(JsonNode body) {
        String from = body.path("cloneFrom").asText("");
        if (!from.isEmpty()) get(from); // 404 -> "không tìm thấy bot nguồn"
        JsonNode result;
        try {
            result = call("POST", "/profiles", body);
        } catch (NotFoundException e) {
            throw unsupported(e);
        } catch (AppException e) {
            if (e.code() == ErrorCode.HERMES_UNAVAILABLE && e.getMessage().contains("HTTP 405")) throw unsupported(e);
            throw e;
        }
        log.info("Tạo bot Hermes '{}' (sao chép từ '{}')", body.path("name").asText(), from.isEmpty() ? "-" : from);
        return result;
    }

    /** Xoá hẳn bot (config, khoá API, bộ nhớ, phiên, skill của bot đó). Máy Hermes bắt gửi ?confirm=<tên> để chống bấm nhầm. */
    public JsonNode delete(String name) {
        JsonNode result = call("DELETE", "/profiles/" + name + "?confirm=" + name, null);
        log.warn("Đã xoá bot Hermes '{}'", name);
        return result;
    }

    public JsonNode skills(String name) { return call("GET", "/profiles/" + name + "/skills", null); }

    public JsonNode skill(String name, String skill) { return call("GET", "/profiles/" + name + "/skills/" + skill, null); }

    /** body = {"enabled": {"ten-skill": true|false, ...}}. */
    public JsonNode setSkillsEnabled(String name, JsonNode body) {
        JsonNode result = call("PUT", "/profiles/" + name + "/skills", body);
        log.info("Bật/tắt skill của bot '{}': {}", name, result.path("changed"));
        return result;
    }

    /** body = {enabled?: bool, content?: "SKILL.md mới"}. */
    public JsonNode updateSkill(String name, String skill, JsonNode body) {
        JsonNode result = call("PUT", "/profiles/" + name + "/skills/" + skill, body);
        log.info("Sửa skill '{}' của bot '{}': {}", skill, name, result.path("changed"));
        return result;
    }

    public JsonNode restart(String name) {
        JsonNode result = call("POST", "/profiles/" + name + "/restart", null);
        log.info("Khởi động lại gateway bot Hermes '{}'", name);
        return result;
    }

    /** Giao việc cho bot: chạy một lượt trên máy Hermes, không cần gateway. Gửi lại cùng runId thì nhận run cũ. */
    public JsonNode startRun(String name, String runId, String prompt) {
        return call("POST", "/profiles/" + name + "/runs", mapper.createObjectNode().put("runId", runId).put("prompt", prompt));
    }

    /** status: queued|running|succeeded|failed|timeout|cancelled, kèm output, error, tokens. */
    public JsonNode run(String runId) { return call("GET", "/runs/" + runId, null); }

    public JsonNode stopRun(String runId) { return call("POST", "/runs/" + runId + "/stop", null); }

    /** Kênh trên các máy qua plugin ytb của bot: đi thẳng, không qua AI. Bot không có plugin thì 404. */
    public JsonNode channels(String name) { return call("GET", "/profiles/" + name + "/channels", null); }

    /** action = open | close: mở/đóng Chrome của kênh. machine = null thì tự tìm; trùng tên ở 2 máy thì 409. */
    public JsonNode channelAction(String name, String action, String channel, String machine) {
        var body = mapper.createObjectNode().put("name", channel);
        if (machine != null) body.put("machine", machine);
        JsonNode result = call("POST", "/profiles/" + name + "/channels/" + action, body);
        log.info("Kênh {} {} qua bot '{}': {}", channel, action, name, result.path("status"));
        return result;
    }

    private static AppException unsupported(Throwable cause) {
        return new AppException(ErrorCode.HERMES_UNSUPPORTED,
                "Máy Hermes chưa hỗ trợ tạo bot: hermes_admin.py cần thêm POST /profiles", null, cause);
    }

    private JsonNode call(String method, String path, JsonNode body) {
        if (!configured()) {
            throw new AppException(ErrorCode.HERMES_NOT_CONFIGURED, "Chưa cấu hình HERMES_ADMIN_URL / HERMES_ADMIN_KEY");
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(150)) // restart gateway có thể chậm
                    .header("Authorization", "Bearer " + key());
            if (body != null) b.header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int code = r.statusCode();
            JsonNode out = parse(r.body());
            if (code / 100 == 2) return out;
            String msg = out.path("error").asText("Hermes admin lỗi HTTP " + code);
            if (code == 400) throw new AppException(ErrorCode.VALIDATION, msg);
            if (code == 409) throw new AppException(ErrorCode.CONFLICT, msg);
            if (code == 404) throw new NotFoundException("bot Hermes", path);
            if (code == 503) throw new AppException(ErrorCode.HERMES_UNAVAILABLE, msg); // máy điều khiển kênh tắt: nói rõ máy nào
            log.warn("Hermes admin {} {} -> HTTP {}: {}", method, path, code, msg);
            throw new AppException(ErrorCode.HERMES_UNAVAILABLE, "Máy Hermes từ chối (HTTP " + code + ")");
        } catch (IOException e) {
            throw new AppException(ErrorCode.HERMES_UNAVAILABLE, "Không kết nối được máy Hermes", null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AppException(ErrorCode.HERMES_UNAVAILABLE, "Bị ngắt khi gọi Hermes", null, e);
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.HERMES_NOT_CONFIGURED, "HERMES_ADMIN_URL không hợp lệ", null, e);
        }
    }

    private JsonNode parse(String body) throws JsonProcessingException {
        if (body == null || body.isBlank()) return mapper.createObjectNode();
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            return mapper.createObjectNode(); // phản hồi không phải JSON (vd trang lỗi HTML của proxy)
        }
    }
}
