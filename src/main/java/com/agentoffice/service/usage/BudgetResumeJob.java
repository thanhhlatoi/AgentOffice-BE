package com.agentoffice.service.usage;

import com.agentoffice.config.SchedulerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Mỗi phút quét task WAITING_BUDGET: sang kỳ mới thì ngân sách tự có chỗ. Chống chạy chồng bằng khoá tư vấn trong BudgetService. */
@Component
public class BudgetResumeJob {
    private static final Logger log = LoggerFactory.getLogger(BudgetResumeJob.class);
    private final BudgetService budgets;
    private final SchedulerProperties scheduler;

    public BudgetResumeJob(BudgetService budgets, SchedulerProperties scheduler) {
        this.budgets = budgets;
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelayString = "${agentoffice.budget.resume-interval-ms:60000}")
    void run() {
        if (!scheduler.enabled()) return;
        try { budgets.resumeEligible(); } catch (RuntimeException e) { log.error("Quét task chờ ngân sách lỗi", e); }
    }
}
