package com.agentoffice.domain.knowledge;

import java.util.List;

/**
 * ===== BẠN TỰ VIẾT ===== Cắt văn bản thành chunk cho RAG (prompt-giai-doan-4.md, 4C).
 * Cắt theo đoạn/câu trước, rồi mới theo độ dài; chunk ~ maxChars với overlap; không cắt giữa từ;
 * giữ tiếng Việt có dấu nguyên vẹn (cẩn thận ký tự tổ hợp / surrogate pair).
 */
public class TextChunker {
    public List<String> chunk(String text, int maxChars, int overlapChars) {
        throw new UnsupportedOperationException("TODO: tự viết");
    }
}
