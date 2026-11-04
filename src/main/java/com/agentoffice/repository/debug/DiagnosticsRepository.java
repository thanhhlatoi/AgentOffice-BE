package com.agentoffice.repository.debug;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Truy vấn đọc thẳng SQL để chẩn đoán (không qua entity/service nghiệp vụ nên vẫn chạy khi code nghiệp vụ hỏng). Chỉ profile dev. */
@Profile("dev")
@Repository
public class DiagnosticsRepository {
    private final JdbcTemplate jdbc;

    public DiagnosticsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Long> tasksByStatus() { return counts("SELECT status, count(*) FROM task GROUP BY status ORDER BY status"); }

    public Map<String, Long> goalsByStatus() { return counts("SELECT status, count(*) FROM goal GROUP BY status ORDER BY status"); }

    public long oldestQueuedSeconds() {
        return jdbc.queryForObject("SELECT COALESCE(EXTRACT(EPOCH FROM now() - min(created_at))::bigint, 0) FROM task WHERE status = 'QUEUED'", Long.class);
    }

    public long pendingApprovals() { return jdbc.queryForObject("SELECT count(*) FROM approval WHERE status = 'PENDING'", Long.class); }

    public long latestEventId() { return jdbc.queryForObject("SELECT COALESCE(max(id), 0) FROM domain_event", Long.class); }

    private Map<String, Long> counts(String sql) {
        Map<String, Long> m = new LinkedHashMap<>();
        jdbc.query(sql, rs -> { m.put(rs.getString(1), rs.getLong(2)); });
        return m;
    }
}
