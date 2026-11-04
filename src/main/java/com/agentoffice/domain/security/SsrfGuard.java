package com.agentoffice.domain.security;

import java.net.URI;

/**
 * ===== BẠN TỰ VIẾT ===== Chặn SSRF cho tool gọi HTTP.
 *  - Chỉ http/https. Phân giải DNS rồi kiểm tra IP THẬT: chặn loopback, private (10/8, 172.16/12, 192.168/16),
 *    link-local 169.254.0.0/16 (metadata cloud), IPv6 ::1/fc00::/7.
 *  - Chống DNS rebinding: dùng đúng IP đã kiểm tra khi kết nối. Redirect phải kiểm tra lại từng bước.
 *  - Chặn các dạng viết IP lạ: 0x7f.1, 2130706433, [::ffff:127.0.0.1].
 */
public class SsrfGuard {
    public void assertAllowed(URI uri) {
        throw new UnsupportedOperationException("TODO: tự viết");
    }
}
