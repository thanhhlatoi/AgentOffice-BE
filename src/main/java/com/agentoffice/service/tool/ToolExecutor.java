package com.agentoffice.service.tool;

import java.util.UUID;

/**
 * ===== BẠN TỰ VIẾT ===== Đường ống thực thi tool (prompt-giai-doan-4.md, 4A/4B). Thứ tự cố định:
 *  1. Tool tồn tại & enabled?        2. Agent có grant? (deny-by-default)
 *  3. ScopeValidator khớp args?      4. Taint guard: nội dung từ nguồn không tin cậy +
 *     quyền đọc dữ liệu riêng tư + kênh gửi ra ngoài -> không cho cả 3 cùng lúc ("lethal trifecta").
 *  5. Rủi ro >= EXTERNAL -> ApprovalGate.   6. Giải mã Secret ngay lúc gọi, không đưa vào prompt.
 *  7. Chạy với timeout.                     8. Ghi ToolCallLog (args đã redact).
 */
public class ToolExecutor {
    public String execute(UUID agentId, UUID taskId, String toolName, String argsJson) {
        throw new UnsupportedOperationException("TODO: tự viết");
    }
}
