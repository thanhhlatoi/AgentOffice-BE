package com.agentoffice.service.task;

import com.agentoffice.config.SchedulerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dồn lại thứ tự khi có hạng quá dài (do chèn giữa nhiều lần): đánh số lại TOÀN BỘ task theo đúng thứ tự hiện tại, dùng chung
 * sequence với hạng của task mới (nên task mới luôn nằm sau). Không đổi version: thứ tự tương đối giữ nguyên.
 */
@Component
public class KanbanRankJob {
    private static final Logger log = LoggerFactory.getLogger(KanbanRankJob.class);
    static final int MAX_RANK_LENGTH = 40;

    private final JdbcTemplate jdbc;
    private final SchedulerProperties scheduler;

    public KanbanRankJob(JdbcTemplate jdbc, SchedulerProperties scheduler) {
        this.jdbc = jdbc;
        this.scheduler = scheduler;
    }

    @Scheduled(fixedDelayString = "${agentoffice.kanban.rebalance-interval-ms:3600000}", initialDelay = 120000)
    void run() {
        if (!scheduler.enabled()) return;
        try {
            int n = rebalanceIfNeeded();
            if (n > 0) log.info("Dồn lại thứ tự Kanban cho {} task", n);
        } catch (RuntimeException e) { log.error("Dồn thứ tự Kanban lỗi", e); }
    }

    @Transactional
    public int rebalanceIfNeeded() {
        Integer longest = jdbc.queryForObject("SELECT coalesce(max(length(kanban_rank)), 0) FROM task", Integer.class);
        if (longest == null || longest <= MAX_RANK_LENGTH) return 0;
        long base = jdbc.queryForObject("SELECT nextval('task_rank_seq')", Long.class);
        long count = jdbc.queryForObject("SELECT count(*) FROM task", Long.class);
        jdbc.queryForObject("SELECT setval('task_rank_seq', ?)", Long.class, base + count);   // task mới (sau này) luôn lớn hơn dải vừa cấp
        return jdbc.update("""
                UPDATE task t SET kanban_rank = lpad((? + o.n)::text, 20, '0')
                FROM (SELECT id, row_number() OVER (ORDER BY kanban_rank, created_at, id) AS n FROM task) o
                WHERE o.id = t.id""", base);
    }
}
