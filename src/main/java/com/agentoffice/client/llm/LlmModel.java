package com.agentoffice.client.llm;

/**
 * Một model/combo mà gateway đang phục vụ. id là giá trị dùng cho trường model của request.
 * Các trường còn lại lấy từ capabilities của gateway; null = gateway không báo.
 */
public record LlmModel(String id, String ownedBy, Long contextWindow, Long maxOutput, Boolean vision, Boolean tools, Boolean reasoning) {
    public LlmModel(String id, String ownedBy) { this(id, ownedBy, null, null, null, null, null); }
}
