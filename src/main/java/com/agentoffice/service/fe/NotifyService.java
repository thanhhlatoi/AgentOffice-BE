package com.agentoffice.service.fe;

import com.agentoffice.dto.fe.BizDto;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hộp thư trong ứng dụng (notification.channel = IN_APP) + cài đặt thông báo.
 * Nguồn: đọc outbox (domain_event) theo con trỏ lưu trong system_state, nên không mất thông báo khi BE tắt rồi bật lại.
 * Telegram/Email CHƯA được hỗ trợ (NotificationDispatcher còn là bản "bạn tự viết"): trạng thái luôn NONE.
 */
@Service
public class NotifyService {
    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);
    private static final String CURSOR_KEY = "notif_cursor";
    private static final List<String> LEVELS = List.of("INFO", "SUCCESS", "WARNING", "CRITICAL");
    private static final List<String> TYPES = List.of("goal.done", "task.failed", "task.done", "approval.requested", "budget.soft", "budget.hard", "device.offline");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public NotifyService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ================================================================== đọc / đánh dấu đã đọc
    @Transactional(readOnly = true)
    public BizDto.Page<BizDto.Notification> list(String level, String type, Boolean unread, String cursor, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, level, type, title, coalesce(body, ''), target_kind, target_id, created_at, read_at
                FROM notification WHERE channel = 'IN_APP'""");
        List<Object> args = new ArrayList<>();
        if (level != null && !level.isBlank()) { sql.append(" AND level = ?"); args.add(level.toUpperCase()); }
        if (type != null && !type.isBlank()) { sql.append(" AND type = ?"); args.add(type); }
        if (Boolean.TRUE.equals(unread)) sql.append(" AND read_at IS NULL");
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] c = cursor.split("_", 2);
                sql.append(" AND (created_at, id) < (?, ?)");
                args.add(java.sql.Timestamp.from(Instant.ofEpochSecond(0, Long.parseLong(c[0]) * 1000)));
                args.add(UUID.fromString(c[1]));
            } catch (RuntimeException e) {
                throw new AppException(ErrorCode.VALIDATION, "cursor không hợp lệ");
            }
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit + 1);
        List<BizDto.Notification> rows = jdbc.query(sql.toString(), (rs, i) -> map(rs), args.toArray());
        if (rows.size() <= limit) return new BizDto.Page<>(rows, rows.size(), null);
        List<BizDto.Notification> page = rows.subList(0, limit);
        BizDto.Notification last = page.get(limit - 1);
        long micros = last.createdAt().getEpochSecond() * 1_000_000L + last.createdAt().getNano() / 1000;
        return new BizDto.Page<>(page, page.size(), micros + "_" + last.id());
    }

    private static BizDto.Notification map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String kind = rs.getString(6);
        BizDto.Target target = kind == null || rs.getObject(7) == null ? null : new BizDto.Target(kind, rs.getString(7));
        java.sql.Timestamp read = rs.getTimestamp(9);
        return new BizDto.Notification(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), target,
                rs.getTimestamp(8).toInstant(), read == null ? null : read.toInstant());
    }

    @Transactional(readOnly = true)
    public BizDto.Count unread() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM notification WHERE channel = 'IN_APP' AND read_at IS NULL", Long.class);
        return new BizDto.Count(n == null ? 0 : n);
    }

    @Transactional
    public BizDto.Notification markRead(UUID id) {
        jdbc.update("UPDATE notification SET read_at = coalesce(read_at, now()) WHERE id = ? AND channel = 'IN_APP'", id);
        List<BizDto.Notification> r = jdbc.query("SELECT id, level, type, title, coalesce(body, ''), target_kind, target_id, created_at, read_at FROM notification WHERE id = ? AND channel = 'IN_APP'",
                (rs, i) -> map(rs), id);
        if (r.isEmpty()) throw new com.agentoffice.exception.NotFoundException("notification", id);
        return r.get(0);
    }

    @Transactional
    public BizDto.Count readAll() {
        jdbc.update("UPDATE notification SET read_at = now() WHERE channel = 'IN_APP' AND read_at IS NULL");
        return new BizDto.Count(0);
    }

    // ================================================================== cài đặt
    @Transactional(readOnly = true)
    public ObjectNode settings() {
        List<String> docs = jdbc.queryForList("SELECT doc::text FROM notification_settings WHERE id = 1", String.class);
        ObjectNode doc = defaults();
        if (!docs.isEmpty()) {
            try {
                JsonNode saved = mapper.readTree(docs.get(0));
                for (String k : List.of("rules", "quiet", "digest")) if (saved.has(k)) doc.set(k, saved.get(k));
            } catch (Exception e) {
                log.warn("Không đọc được notification_settings, dùng mặc định: {}", e.getMessage());
            }
        }
        ObjectNode out = mapper.createObjectNode();
        out.set("telegram", mapper.createObjectNode().put("status", "NONE"));
        out.set("email", mapper.createObjectNode().put("status", "NONE"));
        out.set("rules", doc.get("rules"));
        out.set("quiet", doc.get("quiet"));
        out.set("digest", doc.get("digest"));
        return out;
    }

    @Transactional
    public ObjectNode saveSettings(JsonNode body) {
        JsonNode rules = body.get("rules");
        if (rules == null || !rules.isArray() || rules.size() > 100) throw new AppException(ErrorCode.VALIDATION, "rules phải là mảng (tối đa 100)");
        for (JsonNode r : rules) {
            if (!r.hasNonNull("type") || !r.has("channels") || !LEVELS.contains(r.path("minLevel").asText()))
                throw new AppException(ErrorCode.VALIDATION, "Quy tắc cần type, channels và minLevel hợp lệ");
        }
        ObjectNode doc = mapper.createObjectNode();
        doc.set("rules", rules);
        doc.set("quiet", requireObject(body, "quiet"));
        doc.set("digest", requireObject(body, "digest"));
        jdbc.update("INSERT INTO notification_settings (id, doc) VALUES (1, ?::jsonb) ON CONFLICT (id) DO UPDATE SET doc = EXCLUDED.doc, updated_at = now()", doc.toString());
        return settings();
    }

    private JsonNode requireObject(JsonNode body, String k) {
        JsonNode n = body.get(k);
        if (n == null || !n.isObject()) throw new AppException(ErrorCode.VALIDATION, k + " phải là object");
        return n;
    }

    private ObjectNode defaults() {
        ObjectNode doc = mapper.createObjectNode();
        ArrayNode rules = doc.putArray("rules");
        for (String t : TYPES) {
            ObjectNode r = rules.addObject();
            r.put("type", t);
            r.set("channels", mapper.createObjectNode().put("app", true).put("telegram", false).put("email", false));
            r.put("minLevel", "INFO");
        }
        doc.set("quiet", mapper.createObjectNode().put("enabled", false).put("from", "22:00").put("to", "07:00").put("tz", "Asia/Ho_Chi_Minh"));
        doc.set("digest", mapper.createObjectNode().put("enabled", false).put("at", "18:00"));
        return doc;
    }

    // ================================================================== nguồn: outbox -> hộp thư
    /** Con trỏ lưu bền; lần đầu chạy bắt đầu từ cuối outbox (không dựng lại thông báo cho lịch sử cũ). */
    @Scheduled(fixedDelayString = "${agentoffice.events.relay-interval-ms}")
    @Transactional
    public int feedOnce() {
        List<String> cur = jdbc.queryForList("SELECT value FROM system_state WHERE key = ?", String.class, CURSOR_KEY);
        if (cur.isEmpty()) {
            Long max = jdbc.queryForObject("SELECT coalesce(max(id), 0) FROM domain_event", Long.class);
            jdbc.update("INSERT INTO system_state(key, value) VALUES (?, ?) ON CONFLICT (key) DO NOTHING", CURSOR_KEY, String.valueOf(max));
            return 0;
        }
        long after = Long.parseLong(cur.get(0));
        List<Map<String, Object>> evs = jdbc.queryForList(
                "SELECT id, type, aggregate_id, payload::text AS payload FROM domain_event WHERE id > ? ORDER BY id LIMIT 200", after);
        if (evs.isEmpty()) return 0;
        JsonNode settings = settings().get("rules");
        int n = 0;
        for (Map<String, Object> e : evs) {
            if (build(e, settings)) n++;
        }
        jdbc.update("UPDATE system_state SET value = ? WHERE key = ?", String.valueOf(((Number) evs.get(evs.size() - 1).get("id")).longValue()), CURSOR_KEY);
        return n;
    }

    private boolean build(Map<String, Object> e, JsonNode rules) {
        String ev = (String) e.get("type");
        UUID agg = (UUID) e.get("aggregate_id");
        long id = ((Number) e.get("id")).longValue();
        JsonNode p;
        try { p = mapper.readTree((String) e.get("payload")); } catch (Exception ex) { return false; }
        String type, level, kind, title, body = "";
        switch (ev) {
            case "goal.succeeded" -> { type = "goal.done"; level = "SUCCESS"; kind = "GOAL"; title = "Mục tiêu hoàn thành: " + p.path("title").asText("(không tên)"); }
            case "goal.failed" -> { type = "goal.failed"; level = "WARNING"; kind = "GOAL"; title = "Mục tiêu thất bại: " + p.path("title").asText("(không tên)"); body = p.path("error").asText(""); }
            case "task.succeeded" -> { type = "task.done"; level = "SUCCESS"; kind = "TASK"; title = "Task hoàn thành: " + p.path("title").asText("(không tên)"); }
            case "task.failed" -> { type = "task.failed"; level = "WARNING"; kind = "TASK"; title = "Task thất bại"; body = p.path("error").asText(p.path("reason").asText("")); }
            case "approval.requested" -> { type = "approval.requested"; level = "WARNING"; kind = "APPROVAL"; title = "Cần duyệt: " + p.path("preview").asText(p.path("toolName").asText("yêu cầu")); }
            case "budget.warning" -> { type = "budget.soft"; level = "WARNING"; kind = "BUDGET"; title = "Ngân sách sắp hết"; }
            case "budget.exceeded" -> { type = "budget.hard"; level = "CRITICAL"; kind = "BUDGET"; title = "Vượt ngân sách, task bị chặn"; }
            default -> { return false; }
        }
        if (!appAllowed(rules, type, level)) return false;
        if (title.length() > 200) title = title.substring(0, 200);
        UUID target = agg;
        if (target == null && p.hasNonNull(kind.equals("TASK") ? "taskId" : "id")) {
            try { target = UUID.fromString(p.get(kind.equals("TASK") ? "taskId" : "id").asText()); } catch (IllegalArgumentException ignored) { /* không có đích */ }
        }
        return jdbc.update("""
                INSERT INTO notification (type, title, body, dedupe_key, channel, status, level, target_kind, target_id, sent_at)
                VALUES (?, ?, ?, ?, 'IN_APP', 'SENT', ?, ?, ?, now()) ON CONFLICT DO NOTHING""",
                type, title, body, "ev:" + id, level, target == null ? null : kind, target) == 1;
    }

    /** Quy tắc toàn cục của loại sự kiện (không gắn dự án/agent): tắt kênh app hoặc mức thấp hơn minLevel thì bỏ. Không có quy tắc = cho qua. */
    static boolean appAllowed(JsonNode rules, String type, String level) {
        if (rules == null) return true;
        for (JsonNode r : rules) {
            if (!type.equals(r.path("type").asText()) || r.hasNonNull("projectId") || r.hasNonNull("agentId")) continue;
            if (!r.path("channels").path("app").asBoolean(true)) return false;
            return LEVELS.indexOf(level) >= LEVELS.indexOf(r.path("minLevel").asText("INFO"));
        }
        return true;
    }
}
