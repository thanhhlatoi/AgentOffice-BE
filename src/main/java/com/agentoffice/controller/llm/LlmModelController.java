package com.agentoffice.controller.llm;

import com.agentoffice.dto.llm.LlmModelDto;
import com.agentoffice.dto.llm.LlmProviderDto;
import com.agentoffice.dto.llm.ModelTestRequest;
import com.agentoffice.dto.llm.ModelTestResponse;
import com.agentoffice.security.SensitiveAction;
import com.agentoffice.service.llm.LlmModelService;
import com.agentoffice.service.llm.ModelTestService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.agentoffice.service.redis.RedisCoordinator;
import java.time.Duration;

/** /api/fe/llm: FE gọi với VITE_API_BASE_URL=.../api/fe (api/gateway.ts, chế độ live, không có mock dự phòng). */
@RestController
@RequestMapping({"/api/llm", "/api/fe/llm"})
public class LlmModelController {
    private final LlmModelService service;
    private final ModelTestService tester;
    private final RedisCoordinator redis;

    public LlmModelController(LlmModelService service, ModelTestService tester, RedisCoordinator redis) {
        this.service = service;
        this.tester = tester;
        this.redis = redis;
    }

    /** Nhà cung cấp đã kết nối trên gateway (9Router) và số model của mỗi nhà. Rỗng khi agentoffice.llm.provider=fake. */
    @GetMapping("/providers")
    public List<LlmProviderDto> providers() { return service.providers(); }

    /** Model của một nhà cung cấp (bắt buộc ?provider=, không có đường lấy tất cả). `id` điền vào agent.modelProfile. */
    @GetMapping("/models")
    public List<LlmModelDto> models(@RequestParam String provider) { return service.models(provider); }

    /** Gọi thử một model (TỐN token thật, ghi usage purpose=MODEL_TEST). Lỗi gọi => 200 với ok=false; hết ngân sách => 422. */
    @PostMapping("/models/test")
    @SensitiveAction("llm.model-test")
    public ModelTestResponse test(@Valid @RequestBody ModelTestRequest r) {
        redis.requirePermit("model-test", "global", 5, Duration.ofMinutes(1));
        return tester.test(r.model());
    }
}
