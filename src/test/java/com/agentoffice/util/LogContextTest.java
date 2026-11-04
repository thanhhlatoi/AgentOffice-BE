package com.agentoffice.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LogContextTest {
    @Test void setsAndRestoresIncludingNesting() {
        MDC.put("requestId", "outer");
        try (LogContext a = LogContext.with("requestId", "inner", "taskId", "t1", "goalId", null)) {
            assertThat(MDC.get("requestId")).isEqualTo("inner");
            assertThat(MDC.get("taskId")).isEqualTo("t1");
            assertThat(MDC.get("goalId")).isNull();
            try (LogContext b = LogContext.with("taskId", "t2")) { assertThat(MDC.get("taskId")).isEqualTo("t2"); }
            assertThat(MDC.get("taskId")).isEqualTo("t1");
        }
        assertThat(MDC.get("requestId")).isEqualTo("outer");
        assertThat(MDC.get("taskId")).isNull();
        MDC.clear();
    }
}
