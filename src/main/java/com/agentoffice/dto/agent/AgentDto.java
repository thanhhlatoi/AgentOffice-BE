package com.agentoffice.dto.agent;

import java.util.UUID;

public record AgentDto(UUID id, String name, String role, String department, String status) {}
