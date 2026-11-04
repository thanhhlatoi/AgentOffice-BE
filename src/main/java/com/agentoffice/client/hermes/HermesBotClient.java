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
    private final String key;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public HermesBotClient(HermesAdminProperties props, ObjectMapper mapper) {
        this.baseUrl = props.url().trim().replaceAll("/+$", "");
        this.key = props.key().trim();
        this.mapper = mapper;
    }

    public boolean configured() { return !baseUrl.isEmpty() && !key.isEmpty(); }

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

    public JsonNode restart(String name) {
        JsonNode result = call("POST", "/profiles/" + name + "/restart", null);
        log.info("Khởi động lại gateway bot Hermes '{}'", name);
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
                    .header("Authorization", "Bearer " + key);
            if (body != null) b.header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int code = r.statusCode();
            JsonNode out = parse(r.body());
            if (code / 100 == 2) return out;
            String msg = out.path("error").asText("Hermes admin lỗi HTTP " + code);
            if (code == 400) throw new AppException(ErrorCode.VALIDATION, msg);
            if (code == 404) throw new NotFoundException("bot Hermes", path);
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
