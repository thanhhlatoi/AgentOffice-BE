package com.agentoffice.domain.office;

import java.util.List;

/**
 * ===== BẠN TỰ VIẾT ===== Kiểm tra bố cục tầng (prompt-giai-doan-5.md, 5A) trước khi publish.
 * Ví dụ luật: object nằm trong lưới 48x30, không chồng nhau, mỗi bàn chạm được lối đi,
 * có ít nhất 1 cửa/cầu thang, số bàn >= số agent của tầng. Trả danh sách lỗi (rỗng = hợp lệ).
 */
public class LayoutValidator {
    public List<String> validate(String layoutJson, int agentCount) {
        throw new UnsupportedOperationException("TODO: tự viết");
    }
}
