package com.agentoffice.entity.agent;

/** Sự kiện nội bộ (Spring, đồng bộ) sau khi lưu agent mới. Khác DomainEvent/outbox: không đẩy ra FE. */
public record AgentCreated(Agent agent) {}
