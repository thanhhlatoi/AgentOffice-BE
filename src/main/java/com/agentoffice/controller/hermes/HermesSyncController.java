package com.agentoffice.controller.hermes;

import com.agentoffice.service.hermes.HermesSyncService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Hợp đồng FE (src/api/hermes.ts): so khớp agent với bot Hermes, đồng bộ, khởi động lại. */
@RestController
@RequestMapping({"/api", "/api/fe"})
public class HermesSyncController {
    private final HermesSyncService service;

    public HermesSyncController(HermesSyncService service) { this.service = service; }

    @GetMapping("/hermes/status")
    public HermesSyncService.Status status() { return service.status(); }

    @GetMapping("/hermes/agents")
    public List<HermesSyncService.AgentSync> agents() { return service.list(); }

    @PostMapping("/hermes/sync-all")
    public HermesSyncService.SyncAll syncAll() { return service.syncAll(); }

    @PostMapping("/agents/{id}/hermes/sync")
    public HermesSyncService.AgentSync sync(@PathVariable UUID id) { return service.sync(id); }

    // ------------------------------------------------------------------ cấu hình bot Hermes (tab "Bot Hermes" trong màn Cấu hình)
    private static final java.util.Set<String> BOT_FIELDS = java.util.Set.of("model", "provider", "baseUrl", "contextLength");

    @GetMapping("/agents/{id}/hermes/bot")
    public com.fasterxml.jackson.databind.JsonNode bot(@PathVariable UUID id) { return service.bot(id); }

    /**
     * Chỉ sửa được model, provider, baseUrl, contextLength. SOUL và mô tả do agent quyết định (system prompt, vai trò, phòng):
     * sửa ở hồ sơ agent, để hai nơi không lệch nhau.
     */
    @PutMapping("/agents/{id}/hermes/bot")
    public com.fasterxml.jackson.databind.JsonNode updateBot(@PathVariable UUID id, @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        if (!body.isObject() || body.isEmpty()) throw bad("Body phải có ít nhất một trường");
        body.fieldNames().forEachRemaining(f -> {
            if (!BOT_FIELDS.contains(f)) throw bad("Không được sửa trường: " + f + " (chỉ model, provider, baseUrl, contextLength)");
        });
        for (String f : new String[] {"model", "provider", "baseUrl"})
            if (body.has(f) && !body.get(f).isTextual()) throw bad(f + " phải là chuỗi");
        if (body.has("contextLength") && !body.get("contextLength").isInt()) throw bad("contextLength phải là số nguyên");
        return service.updateBot(id, body);
    }

    // ------------------------------------------------------------------ skill của bot Hermes (tab Skill trong FE)
    private static final java.util.regex.Pattern SKILL = java.util.regex.Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$");

    private static String skill(String s) {
        if (!SKILL.matcher(s).matches()) throw new com.agentoffice.exception.AppException(com.agentoffice.exception.ErrorCode.VALIDATION, "Tên skill không hợp lệ");
        return s;
    }

    private static com.agentoffice.exception.AppException bad(String msg) {
        return new com.agentoffice.exception.AppException(com.agentoffice.exception.ErrorCode.VALIDATION, msg);
    }

    @GetMapping("/agents/{id}/hermes/skills")
    public com.fasterxml.jackson.databind.JsonNode skills(@PathVariable UUID id) { return service.skills(id); }

    @GetMapping("/agents/{id}/hermes/skills/{skill}")
    public com.fasterxml.jackson.databind.JsonNode skill(@PathVariable UUID id, @PathVariable String skill) {
        return service.skill(id, skill(skill));
    }

    /** Body: {"enabled": {"ten-skill": true, "skill-khac": false}}. Cần restart bot để có hiệu lực. */
    @PutMapping("/agents/{id}/hermes/skills")
    public com.fasterxml.jackson.databind.JsonNode setSkills(@PathVariable UUID id, @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        var enabled = body.path("enabled");
        if (!enabled.isObject() || enabled.isEmpty()) throw bad("Cần enabled: {ten-skill: true|false}");
        enabled.fields().forEachRemaining(e -> {
            skill(e.getKey());
            if (!e.getValue().isBoolean()) throw bad("Giá trị của " + e.getKey() + " phải là true/false");
        });
        return service.setSkillsEnabled(id, body);
    }

    /** Body: {enabled?: bool, content?: "toàn bộ SKILL.md mới"} (front matter phải giữ nguyên name). */
    @PutMapping("/agents/{id}/hermes/skills/{skill}")
    public com.fasterxml.jackson.databind.JsonNode updateSkill(@PathVariable UUID id, @PathVariable String skill,
                                                              @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        if (!body.isObject() || body.isEmpty()) throw bad("Body phải có enabled và/hoặc content");
        body.fieldNames().forEachRemaining(f -> {
            if (!f.equals("enabled") && !f.equals("content")) throw bad("Không được sửa trường: " + f);
        });
        if (body.has("enabled") && !body.get("enabled").isBoolean()) throw bad("enabled phải là true/false");
        if (body.has("content") && !body.get("content").isTextual()) throw bad("content phải là chuỗi");
        return service.updateSkill(id, skill(skill), body);
    }

    // ------------------------------------------------------------------ kênh YouTube (tab Kênh trong FE)
    private static final java.util.regex.Pattern MACHINE = java.util.regex.Pattern.compile("^[a-z0-9_-]{1,32}$");

    @GetMapping("/agents/{id}/hermes/channels")
    public com.fasterxml.jackson.databind.JsonNode channels(@PathVariable UUID id) { return service.channels(id); }

    /** Mở/đóng Chrome của kênh, không qua AI. Body: {name, machine?}. Chỉ có open và close: đăng video phải qua duyệt. */
    @PostMapping("/agents/{id}/hermes/channels/{action:open|close}")
    public com.fasterxml.jackson.databind.JsonNode channelAction(@PathVariable UUID id, @PathVariable String action,
                                                                @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        var name = body.path("name");
        if (!name.isTextual() || name.asText().isBlank() || name.asText().length() > 120) throw bad("name phải là tên kênh, tối đa 120 ký tự");
        String machine = null;
        if (body.hasNonNull("machine")) {
            machine = body.get("machine").asText();
            if (!body.get("machine").isTextual() || !MACHINE.matcher(machine).matches()) throw bad("machine không hợp lệ");
        }
        return service.channelAction(id, action, name.asText(), machine);
    }

    @PostMapping("/agents/{id}/hermes/restart")
    public HermesSyncService.AgentSync restart(@PathVariable UUID id) { return service.restart(id); }
}
