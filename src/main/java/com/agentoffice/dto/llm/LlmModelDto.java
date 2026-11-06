package com.agentoffice.dto.llm;

/** contextWindow/maxOutput tính bằng token; vision = nhận ảnh. Trường null (gateway không báo) bị bỏ khỏi JSON. */
public record LlmModelDto(String id, String ownedBy, Long contextWindow, Long maxOutput, Boolean vision, Boolean tools, Boolean reasoning) {}
