package com.agentoffice.service.usage;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentoffice.service.project.ProjectService;
import com.agentoffice.support.PostgresIT;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class UsageDemoDataIT extends PostgresIT {
    @Autowired UsageDemoData demo;
    @Autowired UsageService usageService;
    @Autowired ProjectService projects;

    @Test void nothingWithoutAgents() { assertThat(demo.seed()).isZero(); }

    @Test void thirtyDaysOfUsageAndProgressAndRerunningAddsNothing() {
        agent("a"); agent("b"); agent("c");
        int written = demo.seed();
        assertThat(written).isGreaterThan(30 * 6 - 1);

        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh"));
        var byDay = usageService.summary(today.minusDays(35), today, "day", null, null);
        assertThat(byDay).hasSize(30);
        assertThat(byDay).allSatisfy(r -> assertThat(r.calls()).isPositive());
        long cost = byDay.stream().mapToLong(r -> r.costMicroUsd()).sum();
        assertThat(cost).isPositive();
        assertThat(usageService.summary(today.minusDays(35), today, "model", null, null)).extracting(r -> r.key())
                .contains("claude-haiku-5-5", "qwen3:32b");
        assertThat(usageService.unpriced()).as("giá mẫu khớp seed V9").isEmpty();

        UUID project = jdbc.queryForObject("SELECT id FROM project WHERE name = ?", UUID.class, UsageDemoData.PROJECT_NAME);
        assertThat(projects.history(project, today.minusDays(35), today)).hasSize(30);
        assertThat(usageService.summary(today.minusDays(35), today, "project", null, null)).extracting(r -> r.key()).containsExactly(project.toString());

        assertThat(demo.seed()).as("chạy lại không ghi thêm").isZero();
        assertThat(usageService.summary(today.minusDays(35), today, "day", null, null).stream().mapToLong(r -> r.costMicroUsd()).sum()).isEqualTo(cost);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project WHERE name = ?", Long.class, UsageDemoData.PROJECT_NAME)).isEqualTo(1);
    }
}
