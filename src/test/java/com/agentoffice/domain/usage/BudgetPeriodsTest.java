package com.agentoffice.domain.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class BudgetPeriodsTest {
    private static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");

    @Test void dayFollowsTheReportZoneNotUtc() {
        // 16:59Z vẫn là ngày 10 ở Hồ Chí Minh (23:59); 17:00Z đã sang ngày 11 (00:00)
        assertThat(BudgetPeriods.startOf(BudgetPeriodType.DAY, Instant.parse("2026-03-10T16:59:59Z"), HCM)).isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(BudgetPeriods.startOf(BudgetPeriodType.DAY, Instant.parse("2026-03-10T17:00:00Z"), HCM)).isEqualTo(LocalDate.of(2026, 3, 11));
    }

    @Test void monthStartsOnTheFirstInTheReportZone() {
        assertThat(BudgetPeriods.startOf(BudgetPeriodType.MONTH, Instant.parse("2026-03-31T16:59:00Z"), HCM)).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(BudgetPeriods.startOf(BudgetPeriodType.MONTH, Instant.parse("2026-03-31T17:00:00Z"), HCM)).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test void totalNeverChanges() {
        assertThat(BudgetPeriods.startOf(BudgetPeriodType.TOTAL, Instant.parse("2026-03-10T00:00:00Z"), HCM))
                .isEqualTo(BudgetPeriods.startOf(BudgetPeriodType.TOTAL, Instant.parse("2031-12-31T00:00:00Z"), HCM))
                .isEqualTo(BudgetPeriods.TOTAL_START);
    }
}
