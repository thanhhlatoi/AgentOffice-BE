package com.agentoffice.domain.task;

import java.util.UUID;

/** Thông tin agent đưa cho Planner (không có dữ liệu nhạy cảm). */
public record AgentInfo(UUID id, String name, String role, String department, String status) {}
