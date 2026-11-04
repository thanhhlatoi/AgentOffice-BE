package com.agentoffice.service.notification;

/**
 * ===== BẠN TỰ VIẾT ===== (prompt-giai-doan-3.md, 3C)
 *  - Dedupe theo dedupeKey trong cửa sổ thời gian.
 *  - Storm collapse: >N thông báo cùng loại trong 1 phút -> gộp thành 1 "còn X thông báo nữa".
 *  - Quiet hours: trong giờ yên lặng chỉ gửi mức URGENT, còn lại hoãn tới hết giờ.
 *  - Kênh lỗi -> retry có giới hạn, rồi FAILED. Telegram CHỈ thông báo, không có nút duyệt.
 */
public class NotificationDispatcher {
    public void dispatchPending() {
        throw new UnsupportedOperationException("TODO: tự viết");
    }
}
