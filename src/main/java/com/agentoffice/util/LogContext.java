package com.agentoffice.util;

import java.util.HashMap;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Đặt ngữ cảnh log (taskId, agentId, goalId, requestId...) cho một đoạn xử lý nền, tự khôi phục khi xong.
 * Dùng với try-with-resources ở đầu mỗi worker/job:  try (var ctx = LogContext.with("taskId", id, "agentId", agent)) { ... }
 * Giá trị null bị bỏ qua. Luồng worker KHÔNG kế thừa MDC của request nên phải đặt lại ở đây.
 */
public final class LogContext implements AutoCloseable {
    private final Map<String, String> previous = new HashMap<>();

    private LogContext() {}

    public static LogContext with(Object... keyValues) {
        if (keyValues.length % 2 != 0) throw new IllegalArgumentException("cần cặp key,value");
        LogContext c = new LogContext();
        for (int i = 0; i < keyValues.length; i += 2) {
            String k = String.valueOf(keyValues[i]);
            c.previous.putIfAbsent(k, MDC.get(k));
            if (keyValues[i + 1] != null) MDC.put(k, String.valueOf(keyValues[i + 1]));
        }
        return c;
    }

    @Override
    public void close() {
        previous.forEach((k, v) -> { if (v == null) MDC.remove(k); else MDC.put(k, v); });
    }
}
