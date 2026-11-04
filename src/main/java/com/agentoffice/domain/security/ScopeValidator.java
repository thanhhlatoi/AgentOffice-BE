package com.agentoffice.domain.security;

import java.nio.file.Path;

/**
 * ===== BẠN TỰ VIẾT ===== Kiểm tra args có nằm trong scope được cấp.
 *  - Đường dẫn file: resolve + normalize + toRealPath rồi mới startsWith(root). Chặn "../", symlink thoát root.
 *  - Host: so khớp chính xác hoặc subdomain đúng nghĩa ("evil-example.com" không khớp "example.com").
 */
public class ScopeValidator {
    public Path resolveInside(Path root, String userPath) {
        throw new UnsupportedOperationException("TODO: tự viết");
    }

    public boolean hostAllowed(String host, java.util.List<String> allowedHosts) {
        throw new UnsupportedOperationException("TODO: tự viết");
    }
}
