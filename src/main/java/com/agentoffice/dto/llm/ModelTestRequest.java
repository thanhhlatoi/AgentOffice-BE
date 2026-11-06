package com.agentoffice.dto.llm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ModelTestRequest(@NotBlank @Size(max = 100) String model) {}
