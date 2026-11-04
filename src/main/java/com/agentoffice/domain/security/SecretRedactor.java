package com.agentoffice.domain.security;

/**
 * ===== BẠN TỰ VIẾT ===== Che bí mật TRƯỚC KHI lưu hội thoại/log (prompt-giai-doan-3.md, 3B).
 * Che: "Bearer xxx", "sk-...", "api_key=...", số thẻ 13-19 chữ số, chuỗi giống JWT. Thay bằng "[REDACTED]".
 * Gợi ý: danh sách Pattern biên dịch sẵn (static final), chạy lần lượt; test cả trường hợp KHÔNG che nhầm văn bản thường.
 */
public class SecretRedactor {
    public String redact(String text) {
        throw new UnsupportedOperationException("TODO: tự viết");
    }
}
