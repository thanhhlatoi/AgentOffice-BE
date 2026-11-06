package com.agentoffice.security;

import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Xác thực chủ tối thiểu bằng một mã bí mật (OWNER_TOKEN trong .env), gửi qua header "Authorization: Bearer ...".
 * Chưa đặt OWNER_TOKEN thì việc sửa cài đặt bị từ chối hoàn toàn (an toàn mặc định). So sánh thời gian không đổi.
 * ponytail: một mã dùng chung, không có tài khoản/phiên/giới hạn số lần thử; đủ cho mạng LAN một chủ, chưa phải đăng nhập đầy đủ.
 */
@Component
public class OwnerAuth {
    private final byte[] token;

    public OwnerAuth(@Value("${agentoffice.owner-token:}") String token) {
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    public boolean enabled() { return token.length >= 16; }

    /** Ném UNAUTHORIZED nếu chưa bật hoặc mã sai. Thông báo không phân biệt "thiếu" với "sai" để khỏi lộ thông tin. */
    public void require(String authorizationHeader) {
        if (!enabled()) throw new AppException(ErrorCode.UNAUTHORIZED, "Chưa đặt OWNER_TOKEN (≥16 ký tự) trong .env nên chưa sửa được cài đặt từ giao diện");
        String got = authorizationHeader != null && authorizationHeader.startsWith("Bearer ") ? authorizationHeader.substring(7).trim() : "";
        if (!MessageDigest.isEqual(got.getBytes(StandardCharsets.UTF_8), token)) throw new AppException(ErrorCode.UNAUTHORIZED, "Mã chủ không đúng");
    }
}
