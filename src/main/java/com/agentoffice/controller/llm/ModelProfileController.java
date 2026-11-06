package com.agentoffice.controller.llm;

import com.agentoffice.dto.llm.ModelProfileDto;
import com.agentoffice.service.llm.ModelProfileService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/model-profiles")
public class ModelProfileController {
    private final ModelProfileService profiles;

    public ModelProfileController(ModelProfileService profiles) { this.profiles = profiles; }

    /** Mọi cấu hình model kèm trạng thái: có trên gateway không (onGateway), có giá hiệu lực không (priced). */
    @GetMapping
    public List<ModelProfileDto> list() { return profiles.list(); }
}
