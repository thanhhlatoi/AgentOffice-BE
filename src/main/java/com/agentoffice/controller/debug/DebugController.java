package com.agentoffice.controller.debug;

import com.agentoffice.service.debug.DiagnosticsService;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Chẩn đoán nhanh, CHỈ profile dev. Mỏng: chỉ gọi service. */
@Profile("dev")
@RestController
@RequestMapping("/api/debug")
public class DebugController {
    private final DiagnosticsService diagnostics;

    public DebugController(DiagnosticsService diagnostics) { this.diagnostics = diagnostics; }

    @GetMapping("/queue")
    public Map<String, Object> queue() { return diagnostics.queue(); }

    @GetMapping("/outbox-lag")
    public Map<String, Object> outboxLag() { return diagnostics.outboxLag(); }
}
