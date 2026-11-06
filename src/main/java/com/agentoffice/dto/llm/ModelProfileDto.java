package com.agentoffice.dto.llm;

import java.time.Instant;

/**
 * Một cấu hình model (bảng model_profile). Tên profile là chuỗi gửi sang gateway làm trường model.
 * onGateway: null = không biết (provider=fake hoặc gateway không trả lời), true/false = có/không có trong danh sách model của gateway.
 * priced: đang có bản giá hiệu lực cho (provider, model) của profile; priceEffectiveFrom null khi chưa có.
 */
public record ModelProfileDto(String name, String provider, String model, boolean isLocal, String escalateTo,
                              Boolean onGateway, boolean priced, Instant priceEffectiveFrom) {}
