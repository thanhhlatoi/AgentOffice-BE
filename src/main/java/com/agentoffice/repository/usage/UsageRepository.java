package com.agentoffice.repository.usage;

import com.agentoffice.dto.usage.UsageDto;
import com.agentoffice.dto.usage.UsageEntry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JdbcTemplate thay vì JPA: cần ON CONFLICT (idempotent, nguyên tử) và truy vấn gộp theo cột tự chọn. */
@Repository
public class UsageRepository {
    public static final UUID NONE = new UUID(0, 0);   // thay NULL trong khoá chính usage_daily

    private final JdbcTemplate jdbc;

    public UsageRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** @return false nếu requestId đã được ghi trước đó (không ghi lần hai). */
    public boolean insertRecord(UsageEntry e) {
        return jdbc.update("""
                INSERT INTO usage_record (request_id, occurred_at, agent_id, task_id, goal_id, project_id, department_id,
                    provider, model, purpose, prompt_tokens, completion_tokens, cached_tokens, estimated, cost_micro_usd,
                    price_id, priced, latency_ms, status, error_code)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (request_id) DO NOTHING""",
                e.requestId(), utc(e.occurredAt()), e.agentId(), e.taskId(), e.goalId(), e.projectId(), e.departmentId(),
                e.provider(), e.model(), e.purpose(), e.promptTokens(), e.completionTokens(), e.cachedTokens(), e.estimated(),
                e.costMicroUsd(), e.priceId(), e.priced(), e.latencyMs(), e.status(), e.errorCode()) == 1;
    }

    /** Cộng dồn nguyên tử bằng ON CONFLICT DO UPDATE (không đọc-rồi-ghi). */
    public void addDaily(LocalDate day, UsageEntry e) {
        jdbc.update("""
                INSERT INTO usage_daily (day, agent_id, project_id, model, department_id, calls, errors, prompt_tokens,
                    completion_tokens, cost_micro_usd, unpriced_calls)
                VALUES (?,?,?,?,?,1,?,?,?,?,?)
                ON CONFLICT (day, agent_id, project_id, model) DO UPDATE SET
                    calls = usage_daily.calls + 1,
                    errors = usage_daily.errors + EXCLUDED.errors,
                    prompt_tokens = usage_daily.prompt_tokens + EXCLUDED.prompt_tokens,
                    completion_tokens = usage_daily.completion_tokens + EXCLUDED.completion_tokens,
                    cost_micro_usd = usage_daily.cost_micro_usd + EXCLUDED.cost_micro_usd,
                    unpriced_calls = usage_daily.unpriced_calls + EXCLUDED.unpriced_calls,
                    department_id = COALESCE(usage_daily.department_id, EXCLUDED.department_id)""",
                java.sql.Date.valueOf(day), e.agentId() == null ? NONE : e.agentId(), e.projectId() == null ? NONE : e.projectId(),
                e.model(), e.departmentId(), e.isError() ? 1 : 0, e.promptTokens(), e.completionTokens(), e.costMicroUsd(),
                e.priced() ? 0 : 1);
    }

    public List<UsageDto.SummaryRow> summary(LocalDate from, LocalDate to, String groupBy, UUID agentId, UUID projectId) {
        String key = switch (groupBy) {                       // danh sách trắng, không nối chuỗi từ người dùng
            case "day" -> "day::text";
            case "agent" -> "agent_id::text";
            case "project" -> "project_id::text";
            case "department" -> "department_id::text";
            case "model" -> "model";
            default -> throw new IllegalArgumentException(groupBy);
        };
        StringBuilder sql = new StringBuilder("SELECT " + key + " AS k, sum(calls), sum(errors), sum(prompt_tokens), sum(completion_tokens), "
                + "sum(cost_micro_usd), sum(unpriced_calls) FROM usage_daily WHERE day BETWEEN ? AND ?");
        List<Object> args = new ArrayList<>(List.of(java.sql.Date.valueOf(from), java.sql.Date.valueOf(to)));
        if (agentId != null) { sql.append(" AND agent_id = ?"); args.add(agentId); }
        if (projectId != null) { sql.append(" AND project_id = ?"); args.add(projectId); }
        sql.append(" GROUP BY k ORDER BY k");
        return jdbc.query(sql.toString(), (rs, i) -> new UsageDto.SummaryRow(rs.getString(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7)), args.toArray());
    }

    /** Keyset: (occurred_at, id) giảm dần; cursor là cặp cuối của trang trước. Lấy limit+1 dòng để biết còn trang sau. */
    public List<UsageDto.RecordRow> records(Instant beforeAt, UUID beforeId, UUID agentId, UUID projectId, UUID taskId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, request_id, occurred_at, agent_id, task_id, goal_id, project_id, provider, model, purpose,
                       prompt_tokens, completion_tokens, cached_tokens, estimated, cost_micro_usd, priced, latency_ms, status, error_code
                FROM usage_record WHERE true""");
        List<Object> args = new ArrayList<>();
        if (beforeAt != null) { sql.append(" AND (occurred_at, id) < (?, ?)"); args.add(utc(beforeAt)); args.add(beforeId); }
        if (agentId != null) { sql.append(" AND agent_id = ?"); args.add(agentId); }
        if (projectId != null) { sql.append(" AND project_id = ?"); args.add(projectId); }
        if (taskId != null) { sql.append(" AND task_id = ?"); args.add(taskId); }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ?");
        args.add(limit + 1);
        return jdbc.query(sql.toString(), (rs, i) -> new UsageDto.RecordRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getTimestamp(3).toInstant(), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class),
                rs.getObject(7, UUID.class), rs.getString(8), rs.getString(9), rs.getString(10), rs.getLong(11), rs.getLong(12),
                rs.getLong(13), rs.getBoolean(14), rs.getLong(15), rs.getBoolean(16), rs.getLong(17), rs.getString(18), rs.getString(19)),
                args.toArray());
    }

    /** Model đã gọi mà chưa có giá, và hiện vẫn chưa có bản giá đang mở. */
    public List<UsageDto.UnpricedRow> unpriced() {
        return jdbc.query("""
                SELECT u.provider, u.model, count(*), min(u.occurred_at), max(u.occurred_at)
                FROM usage_record u
                WHERE NOT u.priced AND NOT EXISTS (SELECT 1 FROM model_price p WHERE p.provider = u.provider
                      AND p.model = u.model AND p.effective_to IS NULL)
                GROUP BY u.provider, u.model ORDER BY max(u.occurred_at) DESC""",
                (rs, i) -> new UsageDto.UnpricedRow(rs.getString(1), rs.getString(2), rs.getLong(3),
                        rs.getTimestamp(4).toInstant(), rs.getTimestamp(5).toInstant()));
    }

    private static OffsetDateTime utc(Instant t) { return t.atOffset(ZoneOffset.UTC); }
}
