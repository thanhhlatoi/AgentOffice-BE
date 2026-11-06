package com.agentoffice.service.fe;

import com.agentoffice.dto.fe.BizDto;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.util.TextNormalizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lịch sử hội thoại cho FE: danh sách có lọc/tìm không dấu, chi tiết, tin nhắn theo trang, xoá. Nội dung đã được che bí mật lúc ghi. */
@Service
@Transactional(readOnly = true)
public class HistoryService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public HistoryService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public record Filter(String q, UUID agentId, UUID departmentId, UUID projectId, UUID goalId, UUID taskId, String from, String to,
                         String role, Boolean hasError, String tool) {}

    public BizDto.Page<BizDto.Conversation> list(Filter f, String cursor, int limit) {
        StringBuilder where = new StringBuilder(" WHERE true");
        List<Object> args = new ArrayList<>();
        if (f.agentId() != null) { where.append(" AND c.agent_id = ?"); args.add(f.agentId()); }
        if (f.departmentId() != null) { where.append(" AND a.department_id = ?"); args.add(f.departmentId()); }
        if (f.projectId() != null) { where.append(" AND t.project_id = ?"); args.add(f.projectId()); }
        if (f.goalId() != null) { where.append(" AND t.goal_id = ?"); args.add(f.goalId()); }
        if (f.taskId() != null) { where.append(" AND c.task_id = ?"); args.add(f.taskId()); }
        if (f.from() != null && !f.from().isBlank()) { where.append(" AND coalesce(s.last_at, c.created_at) >= ?"); args.add(java.sql.Timestamp.from(day(f.from()).atStartOfDay(ZONE).toInstant())); }
        if (f.to() != null && !f.to().isBlank()) { where.append(" AND coalesce(s.last_at, c.created_at) < ?"); args.add(java.sql.Timestamp.from(day(f.to()).plusDays(1).atStartOfDay(ZONE).toInstant())); }
        if (Boolean.TRUE.equals(f.hasError())) where.append(" AND coalesce(s.err, false)");
        if (f.role() != null && !f.role().isBlank()) { where.append(" AND EXISTS (SELECT 1 FROM message m WHERE m.conversation_id = c.id AND m.role = ?)"); args.add(f.role().toUpperCase(Locale.ROOT)); }
        if (f.tool() != null && !f.tool().isBlank()) { where.append(" AND EXISTS (SELECT 1 FROM message m WHERE m.conversation_id = c.id AND m.tool_name = ?)"); args.add(f.tool()); }
        String needle = f.q() == null ? "" : TextNormalizer.forSearch(f.q());
        if (!needle.isEmpty()) {
            where.append(" AND (unaccent(lower(coalesce(t.title, ''))) LIKE ? ESCAPE '\\' OR EXISTS (SELECT 1 FROM message m WHERE m.conversation_id = c.id AND m.content_search LIKE ? ESCAPE '\\'))");
            String like = "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            args.add(like);
            args.add(like);
        }
        String base = """
                SELECT c.id, coalesce(t.title, 'Hội thoại') AS title, c.agent_id, t.goal_id, c.task_id, t.project_id, c.created_at AS started,
                       coalesce(s.last_at, c.created_at) AS updated, coalesce(s.cnt, 0) AS cnt, coalesce(s.tok, 0) AS tok,
                       coalesce(s.cost, 0) AS cost, coalesce(s.err, false) AS err, coalesce(s.tools, '{}') AS tools
                FROM conversation c LEFT JOIN task t ON t.id = c.task_id LEFT JOIN agent a ON a.id = c.agent_id
                LEFT JOIN LATERAL (SELECT max(m.created_at) AS last_at, count(*) AS cnt, sum(m.tokens) AS tok, sum(m.cost_micro) AS cost,
                                          bool_or(m.has_error) AS err, array_remove(array_agg(DISTINCT m.tool_name), NULL) AS tools
                                   FROM message m WHERE m.conversation_id = c.id) s ON true""" + where;
        StringBuilder sql = new StringBuilder("SELECT * FROM (" + base + ") x WHERE true");
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] k = cursor.split("_", 2);
                sql.append(" AND (x.updated, x.id) < (?, ?)");
                args.add(java.sql.Timestamp.from(Instant.ofEpochSecond(0, Long.parseLong(k[0]) * 1000)));
                args.add(UUID.fromString(k[1]));
            } catch (RuntimeException e) {
                throw new AppException(ErrorCode.VALIDATION, "cursor không hợp lệ");
            }
        }
        sql.append(" ORDER BY x.updated DESC, x.id DESC LIMIT ?");
        args.add(limit + 1);
        List<BizDto.Conversation> rows = jdbc.query(sql.toString(), (rs, i) -> conversation(rs, needle), args.toArray());
        if (rows.size() <= limit) return new BizDto.Page<>(rows, rows.size(), null);
        List<BizDto.Conversation> page = rows.subList(0, limit);
        Instant last = page.get(limit - 1).updatedAt();
        return new BizDto.Page<>(page, page.size(), (last.getEpochSecond() * 1_000_000L + last.getNano() / 1000) + "_" + page.get(limit - 1).id());
    }

    private BizDto.Conversation conversation(java.sql.ResultSet rs, String needle) throws java.sql.SQLException {
        UUID id = rs.getObject("id", UUID.class);
        Object[] tools = (Object[]) rs.getArray("tools").getArray();
        List<String> toolList = new ArrayList<>();
        for (Object t : tools) toolList.add(String.valueOf(t));
        return new BizDto.Conversation(id.toString(), rs.getString("title"), str(rs.getObject("agent_id", UUID.class)), str(rs.getObject("goal_id", UUID.class)),
                str(rs.getObject("task_id", UUID.class)), str(rs.getObject("project_id", UUID.class)), rs.getTimestamp("started").toInstant(),
                rs.getTimestamp("updated").toInstant(), rs.getLong("cnt"), rs.getLong("tok"), rs.getLong("cost"), rs.getBoolean("err"), toolList,
                needle.isEmpty() ? null : snippet(id, needle));
    }

    /** Đoạn trích quanh lần khớp đầu tiên (so khớp trên bản đã bỏ dấu; bỏ dấu giữ nguyên độ dài nên vị trí dùng được cho bản gốc). */
    private String snippet(UUID conv, String needle) {
        List<String> contents = jdbc.queryForList("SELECT content FROM message WHERE conversation_id = ? AND content_search LIKE ? ESCAPE '\\' ORDER BY created_at LIMIT 1",
                String.class, conv, "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        if (contents.isEmpty()) return null;
        String c = contents.get(0);
        int at = TextNormalizer.forSearch(c).indexOf(needle);
        at = Math.max(0, Math.min(at, c.length()));
        int from = Math.max(0, at - 40);
        int to = Math.min(c.length(), at + needle.length() + 80);
        return (from > 0 ? "…" : "") + c.substring(from, to).replace('\n', ' ') + (to < c.length() ? "…" : "");
    }

    public BizDto.Conversation get(UUID id) {
        List<BizDto.Conversation> one = jdbc.query("""
                SELECT c.id, coalesce(t.title, 'Hội thoại') AS title, c.agent_id, t.goal_id, c.task_id, t.project_id, c.created_at AS started,
                       coalesce(s.last_at, c.created_at) AS updated, coalesce(s.cnt, 0) AS cnt, coalesce(s.tok, 0) AS tok,
                       coalesce(s.cost, 0) AS cost, coalesce(s.err, false) AS err, coalesce(s.tools, '{}') AS tools
                FROM conversation c LEFT JOIN task t ON t.id = c.task_id
                LEFT JOIN LATERAL (SELECT max(m.created_at) AS last_at, count(*) AS cnt, sum(m.tokens) AS tok, sum(m.cost_micro) AS cost,
                                          bool_or(m.has_error) AS err, array_remove(array_agg(DISTINCT m.tool_name), NULL) AS tools
                                   FROM message m WHERE m.conversation_id = c.id) s ON true
                WHERE c.id = ?""", (rs, i) -> conversation(rs, ""), id);
        if (one.isEmpty()) throw new NotFoundException("conversation", id);
        return one.get(0);
    }

    public BizDto.Page<BizDto.Message> messages(UUID id, String cursor, int limit) {
        get(id);
        StringBuilder sql = new StringBuilder("""
                SELECT id, role, content, created_at, tokens, cost_micro, tool_name, tool_args::text, truncated
                FROM message WHERE conversation_id = ? AND role <> 'SYSTEM'""");
        List<Object> args = new ArrayList<>(List.of(id));
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] k = cursor.split("_", 2);
                sql.append(" AND (created_at, id) > (?, ?)");
                args.add(java.sql.Timestamp.from(Instant.ofEpochSecond(0, Long.parseLong(k[0]) * 1000)));
                args.add(UUID.fromString(k[1]));
            } catch (RuntimeException e) {
                throw new AppException(ErrorCode.VALIDATION, "cursor không hợp lệ");
            }
        }
        sql.append(" ORDER BY created_at, id LIMIT ?");
        args.add(limit + 1);
        List<BizDto.Message> rows = jdbc.query(sql.toString(), (rs, i) -> new BizDto.Message(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toInstant(), rs.getLong(5), rs.getLong(6), rs.getString(7), toolArgs(rs.getString(8)), rs.getBoolean(9)), args.toArray());
        if (rows.size() <= limit) return new BizDto.Page<>(rows, rows.size(), null);
        List<BizDto.Message> page = rows.subList(0, limit);
        Instant last = page.get(limit - 1).createdAt();
        return new BizDto.Page<>(page, page.size(), (last.getEpochSecond() * 1_000_000L + last.getNano() / 1000) + "_" + page.get(limit - 1).id());
    }

    private Map<String, Object> toolArgs(String json) {
        if (json == null) return null;
        try { return mapper.readValue(json, new TypeReference<Map<String, Object>>() {}); } catch (Exception e) { return null; }
    }

    @Transactional
    public void delete(UUID id) {
        get(id);
        jdbc.update("DELETE FROM conversation WHERE id = ?", id);     // message xoá theo (ON DELETE CASCADE)
    }

    private static LocalDate day(String s) {
        try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s); }
        catch (RuntimeException e) { throw new AppException(ErrorCode.VALIDATION, "Ngày không hợp lệ: " + s); }
    }

    private static String str(UUID id) { return id == null ? null : id.toString(); }
}
