package com.agentoffice.domain.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Ngày bắt đầu của kỳ ngân sách chứa `at`, theo múi giờ báo cáo. Khoá của budget_counter.period_start. */
public final class BudgetPeriods {
    /** Kỳ TOTAL dùng một ngày cố định để mọi lần gọi trỏ vào cùng một dòng counter. */
    public static final LocalDate TOTAL_START = LocalDate.of(1970, 1, 1);

    private BudgetPeriods() {}

    public static LocalDate startOf(BudgetPeriodType type, Instant at, ZoneId zone) {
        LocalDate day = at.atZone(zone).toLocalDate();
        return switch (type) {
            case DAY -> day;
            case MONTH -> day.withDayOfMonth(1);
            case TOTAL -> TOTAL_START;
        };
    }
}
