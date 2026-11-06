package com.agentoffice.controller.fe;

import com.agentoffice.dto.fe.SettingsAiDto;
import com.agentoffice.security.OwnerAuth;
import com.agentoffice.security.SensitiveAction;
import com.agentoffice.service.settings.SettingsAiService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.*;

/**
 * Màn Cài đặt AI của FE (src/api/settingsAi.ts). Đọc/trạng thái/model/thử: mở (chỉ trả địa chỉ, không bao giờ trả khoá).
 * SỬA (PUT) cần mã chủ OWNER_TOKEN trong header Authorization: Bearer ...; chưa đặt OWNER_TOKEN thì không sửa được.
 */
@RestController
@RequestMapping("/api/fe/settings/ai")
public class SettingsAiController {
    private final SettingsAiService service;
    private final OwnerAuth auth;

    public SettingsAiController(SettingsAiService service, OwnerAuth auth) {
        this.service = service;
        this.auth = auth;
    }

    @GetMapping
    public SettingsAiDto.Settings get() { return service.settings(); }

    @PutMapping
    @SensitiveAction("settings.ai.update")
    public SettingsAiDto.Settings put(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody JsonNode patch) {
        auth.require(authorization);
        return service.update(patch);
    }

    @GetMapping("/status")
    public SettingsAiDto.Status status() { return service.status(); }

    @GetMapping("/models")
    public SettingsAiDto.Models models(@RequestParam(defaultValue = "ollama") String source) { return service.models(source); }

    @PostMapping("/test")
    public SettingsAiDto.TestResult test(@RequestBody SettingsAiDto.TestBody body) { return service.test(body); }
}
