package com.agentoffice.exception;

import java.util.Map;

/** Thân lỗi chuẩn. requestId khớp header X-Request-Id và dòng log; details chỉ có khi lỗi mang thêm dữ kiện. */
public record ApiError(String code, String message, String requestId, Map<String, Object> details) {
    public ApiError(String code, String message, String requestId) { this(code, message, requestId, null); }
}
