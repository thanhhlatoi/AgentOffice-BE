package com.agentoffice.controller.hermes;

import com.agentoffice.client.hermes.HermesBotClient;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.service.hermes.HermesSyncService;
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
    private final HermesSyncService sync;

    public HermesBotController(HermesBotClient client, HermesSyncService sync) {
        this.client = client;
        this.sync = sync;
    }

    @GetMapping
    public JsonNode list() { return client.list(); }

    @GetMapping("/{name}")
    public JsonNode get(@PathVariable String name) { return client.get(checked(name)); }

    /** Body: một hoặc nhiều trường trong model, provider, baseUrl, contextLength, soul, description. Bot của agent: model, soul, description sửa ở hồ sơ agent (409). */
    @PutMapping("/{name}")
    public JsonNode update(@PathVariable String name, @RequestBody JsonNode body) {
        if (!body.isObject() || body.isEmpty()) throw bad("Body phải là object có ít nhất một trường");
        body.fieldNames().forEachRemaining(f -> {
            if (!EDITABLE.contains(f)) throw bad("Không được sửa trường: " + f);
        });
        return sync.updateNamedBot(checked(name), body);
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

    /** Xoá bot. Không xoá được "default", bot mẫu, bot của agent và bot đang chạy gateway (409). */
    @DeleteMapping("/{name}")
    public JsonNode delete(@PathVariable String name) {
        if ("default".equals(name)) throw bad("Không được xoá bot default");
        return sync.deleteNamedBot(checked(name));
    }

    // ------------------------------------------------------------------ skill của bot
    @GetMapping("/{name}/skills")
    public JsonNode skills(@PathVariable String name) { return client.skills(checked(name)); }

    @GetMapping("/{name}/skills/{skill}")
    public JsonNode skill(@PathVariable String name, @PathVariable String skill) {
        return client.skill(checked(name), skill(skill));
    }

    /** Bật/tắt hàng loạt. Body: {"enabled": {"ten-skill": true, "skill-khac": false}}. Cần restart bot để có hiệu lực. */
    @PutMapping("/{name}/skills")
    public JsonNode setSkills(@PathVariable String name, @RequestBody JsonNode body) {
        if (!body.path("enabled").isObject() || body.path("enabled").isEmpty()) throw bad("Cần enabled: {ten-skill: true|false}");
        body.path("enabled").fields().forEachRemaining(e -> {
            skill(e.getKey());
            if (!e.getValue().isBoolean()) throw bad("Giá trị của " + e.getKey() + " phải là true/false");
        });
        return client.setSkillsEnabled(checked(name), body);
    }

    /** Sửa một skill. Body: {enabled?: bool, content?: "toàn bộ SKILL.md mới"} (front matter phải giữ nguyên name). */
    @PutMapping("/{name}/skills/{skill}")
    public JsonNode updateSkill(@PathVariable String name, @PathVariable String skill, @RequestBody JsonNode body) {
        if (!body.isObject() || body.isEmpty()) throw bad("Body phải có enabled và/hoặc content");
        body.fieldNames().forEachRemaining(f -> {
            if (!f.equals("enabled") && !f.equals("content")) throw bad("Không được sửa trường: " + f);
        });
        if (body.has("enabled") && !body.get("enabled").isBoolean()) throw bad("enabled phải là true/false");
        if (body.has("content") && !body.get("content").isTextual()) throw bad("content phải là chuỗi");
        return client.updateSkill(checked(name), skill(skill), body);
    }

    private static final Pattern SKILL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");

    private static String skill(String s) {
        if (!SKILL.matcher(s).matches()) throw bad("Tên skill không hợp lệ");
        return s;
    }

    private static String checked(String name) {
        if (!NAME.matcher(name).matches()) throw bad("Tên bot không hợp lệ");
        return name;
    }

    private static AppException bad(String msg) { return new AppException(ErrorCode.VALIDATION, msg); }
}
