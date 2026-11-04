package com.agentoffice.controller.hermes;

import com.agentoffice.client.hermes.HermesBotClient;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.*;

/** Các con bot (profile) của Hermes: xem, tạo, sửa, khởi động lại. */
@RestController
@RequestMapping("/api/hermes/bots")
public class HermesBotController {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Set<String> EDITABLE = Set.of("model", "provider", "baseUrl", "contextLength", "soul", "description");

    private final HermesBotClient client;

    public HermesBotController(HermesBotClient client) { this.client = client; }

    @GetMapping
    public JsonNode list() { return client.list(); }

    @GetMapping("/{name}")
    public JsonNode get(@PathVariable String name) { return client.get(checked(name)); }

    /** Body: một hoặc nhiều trường trong model, provider, baseUrl, contextLength, soul, description. */
    @PutMapping("/{name}")
    public JsonNode update(@PathVariable String name, @RequestBody JsonNode body) {
        if (!body.isObject() || body.isEmpty()) throw bad("Body phải là object có ít nhất một trường");
        body.fieldNames().forEachRemaining(f -> {
            if (!EDITABLE.contains(f)) throw bad("Không được sửa trường: " + f);
        });
        return client.update(checked(name), body);
    }

    /**
     * Tạo bot mới. Body: name (bắt buộc), cloneFrom (tùy chọn: sao chép cấu hình, SOUL, skill từ bot có sẵn)
     * và các trường sửa được như PUT. Gateway của bot mới chưa chạy cho tới khi khởi động lại.
     */
    @PostMapping
    public JsonNode create(@RequestBody JsonNode body) {
        if (!body.isObject()) throw bad("Body phải là object");
        if (!NAME.matcher(body.path("name").asText("")).matches()) throw bad("Tên bot chỉ gồm chữ, số, _ và -, tối đa 64 ký tự");
        if (body.hasNonNull("cloneFrom")) checked(body.path("cloneFrom").asText(""));
        body.fieldNames().forEachRemaining(f -> {
            if (!f.equals("name") && !f.equals("cloneFrom") && !EDITABLE.contains(f)) throw bad("Không được đặt trường: " + f);
        });
        return client.create(body);
    }

    /** Đổi model / SOUL xong phải khởi động lại gateway thì bot mới dùng cấu hình mới. Việc đang chạy sẽ bị ngắt. */
    @PostMapping("/{name}/restart")
    public JsonNode restart(@PathVariable String name) { return client.restart(checked(name)); }

    private static String checked(String name) {
        if (!NAME.matcher(name).matches()) throw bad("Tên bot không hợp lệ");
        return name;
    }

    private static AppException bad(String msg) { return new AppException(ErrorCode.VALIDATION, msg); }
}
