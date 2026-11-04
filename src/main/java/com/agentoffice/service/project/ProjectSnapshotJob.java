package com.agentoffice.service.project;

import com.agentoffice.config.SchedulerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Mỗi giờ ghi đè ảnh chụp tiến độ của HÔM NAY; lần ghi cuối của ngày là giá trị lưu lại. */
@Component
public class ProjectSnapshotJob {
    private static final Logger log = LoggerFactory.getLogger(ProjectSnapshotJob.class);
    private final ProjectService projects;
    private final SchedulerProperties scheduler;

    public ProjectSnapshotJob(ProjectService projects, SchedulerProperties scheduler) {
        this.projects = projects;
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelayString = "${agentoffice.project.snapshot-interval-ms:3600000}", initialDelay = 60000)
    void run() {
        if (!scheduler.enabled()) return;
        try { projects.snapshotToday(); } catch (RuntimeException e) { log.error("Ảnh chụp tiến độ dự án lỗi", e); }
    }
}
