package com.agentoffice.config.dev;

import com.agentoffice.service.usage.UsageDemoData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Chạy sau DevDataSeeder (cần agent). Ghi dữ liệu giả 30 ngày; chạy lại không nhân đôi. */
@Profile("dev")
@Component
@Order(100)
public class DevUsageSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DevUsageSeeder.class);
    private final UsageDemoData demo;

    public DevUsageSeeder(UsageDemoData demo) { this.demo = demo; }

    @Override
    public void run(ApplicationArguments args) {
        int n = demo.seed();
        if (n > 0) log.info("DevUsageSeeder: ghi {} dòng usage giả (30 ngày) + dự án mẫu '{}'", n, UsageDemoData.PROJECT_NAME);
    }
}
