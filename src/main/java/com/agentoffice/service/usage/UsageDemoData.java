package com.agentoffice.service.usage;

import com.agentoffice.config.UsageProperties;
import com.agentoffice.dto.usage.UsageEntry;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.repository.agent.AgentRepository;
import com.agentoffice.repository.usage.UsageRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dữ liệu GIẢ 30 ngày (usage + tiến độ dự án) để thử dashboard sau này. Chỉ chạy tự động ở profile dev (config/dev/DevUsageSeeder).
 * Idempotent: requestId sinh từ (ngày, số thứ tự) nên chạy lại không ghi thêm và không cộng đôi UsageDaily.
 * Giá ở đây là giá MẪU cố định, trùng các dòng seed của V9 (chỉ để số liệu trông thật; không đọc bảng model_price).
 */
@Service
public class UsageDemoData {
    public static final String PROJECT_NAME = "Kênh Reddit ES (dữ liệu mẫu)";
    private static final int DAYS = 30;

    private record Model(String provider, String model, long in, long out, int weight) {}
    private static final List<Model> MODELS = List.of(
            new Model("anthropic", "claude-haiku-5-5", 1_000_000, 5_000_000, 60),
            new Model("anthropic", "claude-sonnet-5-5", 3_000_000, 15_000_000, 25),
            new Model("ollama", "qwen3:32b", 0, 0, 15));

    private final AgentRepository agents;
    private final UsageRepository usage;
    private final JdbcTemplate jdbc;
    private final UsageProperties props;
    private final Clock clock;

    public UsageDemoData(AgentRepository agents, UsageRepository usage, JdbcTemplate jdbc, UsageProperties props, Clock clock) {
        this.agents = agents;
        this.usage = usage;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    /** @return số dòng usage MỚI được ghi (0 nếu đã seed rồi hoặc chưa có agent nào). */
    @Transactional
    public int seed() {
        List<Agent> all = agents.findAll();
        if (all.isEmpty()) return 0;
        UUID project = demoProject();
        LocalDate today = clock.instant().atZone(props.reportZone()).toLocalDate();
        Random rnd = new Random(42);
        int written = 0;
        for (int d = DAYS; d >= 1; d--) {
            LocalDate day = today.minusDays(d);
            int calls = 6 + rnd.nextInt(10);
            for (int i = 0; i < calls; i++) {
                Agent a = all.get(rnd.nextInt(all.size()));
                Model m = pick(rnd);
                long in = 400 + rnd.nextInt(3000), out = 100 + rnd.nextInt(1500);
                boolean error = rnd.nextInt(100) < 3;
                long cost = error ? 0 : Math.ceilDiv(in * m.in(), 1_000_000) + Math.ceilDiv(out * m.out(), 1_000_000);
                Instant at = day.atStartOfDay(props.reportZone()).toInstant().plus(8 * 3600L + rnd.nextInt(10 * 3600), ChronoUnit.SECONDS);
                UUID requestId = UUID.nameUUIDFromBytes(("demo:" + day + ":" + i).getBytes(StandardCharsets.UTF_8));
                UsageEntry e = new UsageEntry(requestId, at, a.getId(), null, null, project, a.getDepartmentId(), m.provider(), m.model(),
                        "AGENT_RUN", error ? 0 : in, error ? 0 : out, 0, false, cost, null, true, 300 + rnd.nextInt(4000),
                        error ? "ERROR" : "OK", error ? "TimeoutException" : null);
                if (usage.insertRecord(e)) { usage.addDaily(day, e); written++; }
            }
            // tiến độ tăng dần 0 -> ~70% trong 30 ngày
            int pct = Math.min(100, (DAYS - d + 1) * 70 / DAYS);
            jdbc.update("INSERT INTO project_progress_daily(project_id, day, progress_pct, succeeded, failed, total) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                    project, java.sql.Date.valueOf(day), pct, pct / 5, (DAYS - d) % 9 == 0 ? 1 : 0, 20);
        }
        return written;
    }

    private UUID demoProject() {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM project WHERE name = ?", UUID.class, PROJECT_NAME);
        if (!ids.isEmpty()) return ids.get(0);
        return jdbc.queryForObject("INSERT INTO project(name, description, color, progress_pct, has_plan) VALUES (?, 'Dữ liệu mẫu để thử dashboard', '#16A34A', 70, TRUE) RETURNING id",
                UUID.class, PROJECT_NAME);
    }

    private static Model pick(Random rnd) {
        int r = rnd.nextInt(MODELS.stream().mapToInt(Model::weight).sum());
        for (Model m : MODELS) { r -= m.weight(); if (r < 0) return m; }
        return MODELS.get(0);
    }
}
