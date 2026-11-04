package com.agentoffice.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentoffice.config.ApprovalProperties;
import com.agentoffice.domain.approval.RejectMode;
import com.agentoffice.config.LimitsProperties;
import com.agentoffice.config.SchedulerProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Cấu hình sai phải bị bắt lúc khởi động (Spring chạy đúng các ràng buộc này khi bind @Validated). */
class ConfigValidationTest {
    private final Validator v = Validation.buildDefaultValidatorFactory().getValidator();

    @Test void limits() {
        assertThat(v.validate(new LimitsProperties(3, 20))).isEmpty();
        assertThat(v.validate(new LimitsProperties(0, 20))).hasSize(1);
        assertThat(v.validate(new LimitsProperties(3, 0))).hasSize(1);
    }

    @Test void scheduler() {
        assertThat(v.validate(new SchedulerProperties(true, 1000, 4, List.of(5L, 30L)))).isEmpty();
        assertThat(v.validate(new SchedulerProperties(true, 1, 4, List.of(5L)))).isNotEmpty();
        assertThat(v.validate(new SchedulerProperties(true, 1000, 0, List.of(5L)))).isNotEmpty();
        assertThat(v.validate(new SchedulerProperties(true, 1000, 4, List.of()))).isNotEmpty();
        assertThat(v.validate(new SchedulerProperties(true, 1000, 4, List.of(-1L)))).isNotEmpty();
    }

    @Test void approval() {
        assertThat(v.validate(new ApprovalProperties(true, Duration.ofHours(1), RejectMode.FAIL_TASK))).isEmpty();
        assertThat(v.validate(new ApprovalProperties(true, null, RejectMode.FAIL_TASK))).isNotEmpty();
    }
}
