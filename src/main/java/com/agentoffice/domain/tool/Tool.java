package com.agentoffice.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;

/** Một công cụ có thể bị agent gọi. Hiện thực thật (web/file/mail) làm ở prompt sau; ở đây chỉ có hợp đồng. */
public interface Tool {
    ToolSpec spec();

    /** Mô tả tiếng Việt cho người duyệt, vd "Gửi email tới x@y.com, tiêu đề ...". KHÔNG chứa bí mật. */
    String preview(JsonNode args);

    /**
     * Chạy tool. {@code idempotencyKey} ổn định theo (task, tool, args) để phía tool tự chống chạy trùng nếu cần.
     * Với lời gọi đã duyệt, args là ĐÚNG argsSnapshot đã lưu.
     */
    String execute(JsonNode args, String idempotencyKey);
}
