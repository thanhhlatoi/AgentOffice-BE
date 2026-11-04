package com.agentoffice.controller.office;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** FE lấy giờ server để làm ngày/đêm đồng bộ, không tin đồng hồ máy khách. */
@RestController
public class TimeController {
    @GetMapping("/api/time")
    public Map<String, Object> time() {
        return Map.of("now", Instant.now().toString(), "epochMs", System.currentTimeMillis());
    }
}
