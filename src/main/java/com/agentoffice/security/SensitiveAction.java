package com.agentoffice.security;

import java.lang.annotation.*;

/**
 * Đánh dấu endpoint nhạy cảm (duyệt, pause, đổi policy...). Chưa có xác thực chủ ở prompt này;
 * khi làm bảo mật chỉ cần gắn interceptor/AOP lên annotation này.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SensitiveAction {
    String value();
}
