package com.agentoffice.service.fe;

import com.agentoffice.domain.approval.RiskLevel;
import com.agentoffice.dto.fe.BizDto;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.service.approval.ApprovalPolicyService;
import com.agentoffice.service.audit.PermissionAuditLog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Công cụ và quyền cho FE. LƯU Ý QUAN TRỌNG: ToolGateway hiện CHƯA kiểm tra grant / tool.enabled / cờ quản trị khi agent gọi tool
 * (xem Javadoc của nó: "để dành cho BE-10"). Phần này lưu và tính quyền hiệu lực để hiển thị, kiểm tra thử (/check) và ghi nhật ký;
 * việc ÉP quyền lúc chạy là bước riêng chưa làm.
 */
@Service
public class ToolsService {
    private static final String OWNER = "owner";
    private static final Set<String> READS_EXTERNAL = Set.of("web_search", "web_fetch", "email_read", "youtube_analytics_read");
    private static final Set<String> READS_PRIVATE = Set.of("file_read", "file_list", "sheet_read", "email_read", "db_query", "kb_search", "memory_recall",
            "calendar_read", "infra_log_read");
    private static final Set<String> SENDS_OUT = Set.of("web_fetch", "social_publish", "sandbox_run", "infra_service_restart", "infra_update_apply");
    private static final Map<String, String> GROUPS = Map.ofEntries(Map.entry("web", "Web"), Map.entry("file", "Tệp"), Map.entry("sheet", "Bảng tính"),
            Map.entry("email", "Thư"), Map.entry("db", "Cơ sở dữ liệu"), Map.entry("kb", "Kho tri thức"), Map.entry("memory", "Bộ nhớ"),
            Map.entry("delegate", "Điều phối"), Map.entry("ask", "Điều phối"), Map.entry("calendar", "Lịch"), Map.entry("youtube", "Mạng xã hội"),
            Map.entry("social", "Mạng xã hội"), Map.entry("infra", "Hạ tầng"), Map.entry("payment", "Thanh toán"), Map.entry("sandbox", "Sandbox"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ApprovalPolicyService policies;
    private final PermissionAuditLog audit;

    public ToolsService(JdbcTemplate jdbc, ObjectMapper mapper, ApprovalPolicyService policies, PermissionAuditLog audit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.policies = policies;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ mô hình nội bộ
    record ToolRow(UUID id, String name, String description, RiskLevel risk, boolean enabled, boolean adminFlagOn) {
        boolean requiresAdminFlag() { return risk == RiskLevel.MONEY || risk == RiskLevel.DESTRUCTIVE; }
    }

    record GrantRow(UUID toolId, Map<String, Object> scope, Map<String, Object> limits, boolean alwaysApprove, UUID secretId) {}

    // ================================================================== công cụ
    @Transactional(readOnly = true)
    public List<BizDto.ToolView> tools() {
        Map<UUID, Long> counts = new HashMap<>();
        jdbc.query("SELECT tool_id, count(*) FROM agent_tool_grant GROUP BY tool_id", rs -> { counts.put(rs.getObject(1, UUID.class), rs.getLong(2)); });
        return toolRows().stream().map(t -> view(t, counts.getOrDefault(t.id(), 0L))).toList();
    }

    private BizDto.ToolView view(ToolRow t, long agents) {
        return new BizDto.ToolView(t.id().toString(), t.name(), group(t.name()), t.risk().name(), t.description() == null ? "" : t.description(), Map.of(),
                READS_EXTERNAL.contains(t.name()), READS_PRIVATE.contains(t.name()), SENDS_OUT.contains(t.name()), t.enabled(), t.requiresAdminFlag(),
                t.adminFlagOn(), agents);
    }

    static String group(String name) {
        String prefix = name.contains("_") ? name.substring(0, name.indexOf('_')) : name;
        return GROUPS.getOrDefault(prefix, "Khác");
    }

    @Transactional
    public BizDto.ToolView patchTool(UUID id, BizDto.ToolPatch p) {
        ToolRow t = toolRow(id);
        if (p.enabled() == null && p.adminFlagOn() == null) throw new AppException(ErrorCode.VALIDATION, "Cần enabled hoặc adminFlagOn");
        if (p.adminFlagOn() != null && !t.requiresAdminFlag()) throw new AppException(ErrorCode.VALIDATION, "Chỉ tool MONEY/DESTRUCTIVE mới có cờ quản trị");
        if (p.enabled() != null && p.enabled() != t.enabled()) {
            jdbc.update("UPDATE tool SET enabled = ? WHERE id = ?", p.enabled(), id);
            auditTool("KILL_SWITCH", t, Map.of("enabled", t.enabled()), Map.of("enabled", p.enabled()), p.reason());
        }
        if (p.adminFlagOn() != null && p.adminFlagOn() != t.adminFlagOn()) {
            jdbc.update("UPDATE tool SET admin_flag_on = ? WHERE id = ?", p.adminFlagOn(), id);
            auditTool("ADMIN_FLAG", t, Map.of("adminFlagOn", t.adminFlagOn()), Map.of("adminFlagOn", p.adminFlagOn()), p.reason());
        }
        Long n = jdbc.queryForObject("SELECT count(*) FROM agent_tool_grant WHERE tool_id = ?", Long.class, id);
        return view(toolRow(id), n == null ? 0 : n);
    }

    private void auditTool(String action, ToolRow t, Map<String, Object> before, Map<String, Object> after, String reason) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("toolId", t.id());
        d.put("tool", t.name());
        d.put("before", before);
        d.put("after", after);
        if (reason != null && !reason.isBlank()) d.put("reason", reason.trim());
        audit.record(OWNER, action, d);
    }

    // ================================================================== quyền (grant)
    @Transactional(readOnly = true)
    public List<BizDto.GrantView> grants(UUID agentId) {
        String sql = "SELECT agent_id, tool_id, scope::text, limits::text, always_approve, secret_id, created_at, updated_at FROM agent_tool_grant"
                + (agentId != null ? " WHERE agent_id = ?" : "") + " ORDER BY created_at, tool_id";
        return jdbc.query(sql, (rs, i) -> new BizDto.GrantView(rs.getString(1) + ":" + rs.getString(2), rs.getString(1), rs.getString(2), json(rs.getString(3)),
                json(rs.getString(4)), rs.getBoolean(5), rs.getString(6), rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant()),
                agentId == null ? new Object[0] : new Object[] {agentId});
    }

    @Transactional(readOnly = true)
    public BizDto.Effective effective(UUID agentId) {
        requireAgent(agentId);
        return evaluate(agentId, grantMap(agentId), toolRows());
    }

    @Transactional(readOnly = true)
    public List<BizDto.Effective> effectiveAll() {
        List<ToolRow> tools = toolRows();
        Map<UUID, Map<UUID, GrantRow>> byAgent = new HashMap<>();
        jdbc.query("SELECT agent_id, tool_id, scope::text, limits::text, always_approve, secret_id FROM agent_tool_grant",
                rs -> { byAgent.computeIfAbsent(rs.getObject(1, UUID.class), k -> new LinkedHashMap<>()).put(rs.getObject(2, UUID.class), grantRow(rs)); });
        List<UUID> agents = jdbc.queryForList("SELECT id FROM agent WHERE archived_at IS NULL ORDER BY created_at", UUID.class);
        return agents.stream().map(a -> evaluate(a, byAgent.getOrDefault(a, Map.of()), tools)).toList();
    }

    /** Xem trước: áp thay đổi lên bản sao trong bộ nhớ, KHÔNG ghi gì. */
    @Transactional(readOnly = true)
    public BizDto.Effective preview(UUID agentId, BizDto.PermissionChange change) {
        requireAgent(agentId);
        Map<UUID, GrantRow> g = new LinkedHashMap<>(grantMap(agentId));
        applyInMemory(g, change, toolRows());
        return evaluate(agentId, g, toolRows());
    }

    @Transactional
    public BizDto.Effective saveGrants(UUID agentId, BizDto.PermissionChange change) {
        requireAgent(agentId);
        List<ToolRow> tools = toolRows();
        Map<UUID, GrantRow> before = grantMap(agentId);
        Map<UUID, GrantRow> after = new LinkedHashMap<>(before);
        applyInMemory(after, change, tools);                      // kiểm tra hợp lệ trước khi ghi
        Map<UUID, ToolRow> byId = new HashMap<>();
        tools.forEach(t -> byId.put(t.id(), t));
        for (Map.Entry<UUID, GrantRow> e : after.entrySet()) {
            GrantRow b = before.get(e.getKey());
            GrantRow a = e.getValue();
            if (b != null && same(a, b)) continue;
            jdbc.update("""
                    INSERT INTO agent_tool_grant (agent_id, tool_id, scope, limits, always_approve, secret_id)
                    VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?)
                    ON CONFLICT (agent_id, tool_id) DO UPDATE SET scope = EXCLUDED.scope, limits = EXCLUDED.limits,
                        always_approve = EXCLUDED.always_approve, secret_id = EXCLUDED.secret_id, updated_at = now()""",
                    agentId, e.getKey(), write(a.scope()), write(a.limits()), a.alwaysApprove(), a.secretId());
            auditGrant(b == null ? "GRANT" : "UPDATE", agentId, byId.get(e.getKey()), b, a, change.reason());
        }
        for (Map.Entry<UUID, GrantRow> e : before.entrySet()) {
            if (after.containsKey(e.getKey())) continue;
            jdbc.update("DELETE FROM agent_tool_grant WHERE agent_id = ? AND tool_id = ?", agentId, e.getKey());
            auditGrant("REVOKE", agentId, byId.get(e.getKey()), e.getValue(), null, change.reason());
        }
        return evaluate(agentId, grantMap(agentId), tools);
    }

    private void applyInMemory(Map<UUID, GrantRow> g, BizDto.PermissionChange c, List<ToolRow> tools) {
        Set<UUID> known = new java.util.HashSet<>();
        tools.forEach(t -> known.add(t.id()));
        if (c.remove() != null) for (String r : c.remove()) g.remove(uuid(r));
        if (c.grants() != null) {
            for (BizDto.GrantInput in : c.grants()) {
                UUID tool = uuid(in.toolId());
                if (!known.contains(tool)) throw new NotFoundException("tool", tool);
                Map<String, Object> limits = in.limits() == null ? Map.of() : in.limits();
                validateLimits(limits);
                UUID secret = in.secretId() == null || in.secretId().isBlank() ? null : uuid(in.secretId());
                if (secret != null) {
                    Long n = jdbc.queryForObject("SELECT count(*) FROM secret WHERE id = ?", Long.class, secret);
                    if (n == null || n == 0) throw new NotFoundException("secret", secret);
                }
                Map<String, Object> scope = in.scope() == null ? Map.of() : in.scope();
                if (write(scope).length() > 8_000) throw new AppException(ErrorCode.VALIDATION, "Phạm vi quá lớn");
                g.put(tool, new GrantRow(tool, scope, limits, Boolean.TRUE.equals(in.alwaysApprove()), secret));
            }
        }
    }

    private static void validateLimits(Map<String, Object> limits) {
        for (String k : List.of("maxCallsPerTask", "maxPerMinute")) {
            Object v = limits.get(k);
            if (v != null && !(v instanceof Number n && n.longValue() > 0)) throw new AppException(ErrorCode.VALIDATION, k + " phải là số nguyên dương");
        }
        Object exp = limits.get("expiresAt");
        if (exp != null) {
            try { Instant.parse(String.valueOf(exp)); }
            catch (RuntimeException e) { throw new AppException(ErrorCode.VALIDATION, "expiresAt phải là thời điểm ISO-8601 (UTC)"); }
        }
    }

    private static boolean same(GrantRow a, GrantRow b) {
        return a.scope().equals(b.scope()) && a.limits().equals(b.limits()) && a.alwaysApprove() == b.alwaysApprove()
                && java.util.Objects.equals(a.secretId(), b.secretId());
    }

    private void auditGrant(String action, UUID agentId, ToolRow tool, GrantRow before, GrantRow after, String reason) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("agentId", agentId);
        d.put("toolId", tool == null ? null : tool.id());
        d.put("tool", tool == null ? null : tool.name());
        d.put("before", before == null ? null : describe(before));
        d.put("after", after == null ? null : describe(after));
        if (reason != null && !reason.isBlank()) d.put("reason", reason.trim());
        audit.record(OWNER, action, d);
    }

    private static Map<String, Object> describe(GrantRow g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", g.scope());
        m.put("limits", g.limits());
        m.put("alwaysApprove", g.alwaysApprove());
        return m;
    }

    // ================================================================== tính quyền hiệu lực / kiểm tra thử
    BizDto.Effective evaluate(UUID agentId, Map<UUID, GrantRow> grants, List<ToolRow> tools) {
        List<BizDto.EffectiveTool> out = new ArrayList<>();
        boolean priv = false, ext = false, send = false;
        List<ToolRow> allowedSending = new ArrayList<>();
        Map<UUID, ToolRow> byId = new HashMap<>();
        tools.forEach(t -> byId.put(t.id(), t));
        for (GrantRow g : grants.values()) {
            ToolRow t = byId.get(g.toolId());
            if (t == null) continue;
            String blocked = blockedReason(t, g);
            boolean allowed = blocked == null;
            if (allowed) {
                priv |= READS_PRIVATE.contains(t.name());
                ext |= READS_EXTERNAL.contains(t.name());
                send |= SENDS_OUT.contains(t.name());
            }
            out.add(new BizDto.EffectiveTool(t.id().toString(), allowed, t.risk().name(), false, summary(g.scope()),
                    g.limits().get("expiresAt") == null ? null : String.valueOf(g.limits().get("expiresAt")), blocked));
        }
        boolean trifecta = priv && ext && send;                       // đủ ba cạnh: bắt buộc duyệt mọi tool gửi ra ngoài, không tắt được
        List<BizDto.EffectiveTool> finalList = new ArrayList<>();
        for (BizDto.EffectiveTool e : out) {
            ToolRow t = byId.get(UUID.fromString(e.toolId()));
            GrantRow g = grants.get(t.id());
            boolean needs = e.allowed() && (g.alwaysApprove() || policies.requiresApproval(t.name(), t.risk()) || (trifecta && SENDS_OUT.contains(t.name())));
            finalList.add(new BizDto.EffectiveTool(e.toolId(), e.allowed(), e.risk(), needs, e.scopeSummary(), e.expiresAt(), e.blockedReason()));
        }
        return new BizDto.Effective(agentId.toString(), finalList, priv, ext, send);
    }

    private static String blockedReason(ToolRow t, GrantRow g) {
        if (!t.enabled()) return "Công cụ đang bị tắt khẩn cấp";
        if (t.requiresAdminFlag() && !t.adminFlagOn()) return "Thiếu cờ quản trị (" + t.risk() + ")";
        Object exp = g.limits().get("expiresAt");
        if (exp != null) {
            try { if (Instant.parse(String.valueOf(exp)).isBefore(Instant.now())) return "Quyền đã hết hạn"; }
            catch (RuntimeException e) { return "Hạn quyền không hợp lệ"; }
        }
        return null;
    }

    private static String summary(Map<String, Object> scope) {
        if (scope.isEmpty()) return "";
        List<String> parts = new ArrayList<>();
        scope.forEach((k, v) -> parts.add(k + "=" + (v instanceof List<?> l ? String.join(",", l.stream().map(String::valueOf).toList()) : v)));
        String s = String.join("; ", parts);
        return s.length() > 160 ? s.substring(0, 157) + "…" : s;
    }

    /** Kiểm tra thử: KHÔNG chạy tool, KHÔNG kiểm tra phạm vi của args (ScopeValidator còn là phần "bạn tự viết"). */
    @Transactional(readOnly = true)
    public BizDto.CheckResult check(UUID toolId, BizDto.CheckBody b) {
        UUID agentId = uuid(b.agentId());
        requireAgent(agentId);
        ToolRow t = toolRow(toolId);
        GrantRow g = grantMap(agentId).get(toolId);
        if (g == null) return new BizDto.CheckResult("DENIED", "Agent chưa được cấp công cụ này (mặc định từ chối)");
        BizDto.Effective eff = evaluate(agentId, Map.of(toolId, g), List.of(t));
        BizDto.EffectiveTool e = eff.tools().get(0);
        if (!e.allowed()) return new BizDto.CheckResult("DENIED", e.blockedReason());
        // tam giác rủi ro tính trên TOÀN BỘ quyền của agent
        BizDto.Effective all = effective(agentId);
        boolean needs = all.tools().stream().filter(x -> x.toolId().equals(toolId.toString())).anyMatch(BizDto.EffectiveTool::needsApproval);
        return needs ? new BizDto.CheckResult("NEEDS_APPROVAL", "Lời gọi này phải chờ chủ duyệt") : new BizDto.CheckResult("ALLOWED", "");
    }

    // ================================================================== nhật ký
    @Transactional(readOnly = true)
    public BizDto.Page<BizDto.ToolCall> toolCalls(UUID agentId, UUID toolId, String decision, Boolean denied, String cursor, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT l.id, l.created_at, l.agent_id, t.id AS tool_id, l.outcome, l.reason, l.task_id, l.args_redacted::text
                FROM tool_call_log l LEFT JOIN tool t ON t.name = l.tool_name WHERE true""");
        List<Object> args = new ArrayList<>();
        if (agentId != null) { sql.append(" AND l.agent_id = ?"); args.add(agentId); }
        if (toolId != null) { sql.append(" AND t.id = ?"); args.add(toolId); }
        String d = decision == null ? "" : decision.toUpperCase();
        if (Boolean.TRUE.equals(denied) || "DENIED".equals(d)) sql.append(" AND l.outcome = 'DENIED'");
        else if ("NEEDS_APPROVAL".equals(d)) sql.append(" AND l.outcome = 'WAITING_APPROVAL'");
        else if ("ALLOWED".equals(d)) sql.append(" AND l.outcome IN ('OK', 'ERROR')");
        cursor(sql, args, cursor, "l.created_at", "l.id");
        sql.append(" ORDER BY l.created_at DESC, l.id DESC LIMIT ?");
        args.add(limit + 1);
        List<BizDto.ToolCall> rows = jdbc.query(sql.toString(), (rs, i) -> new BizDto.ToolCall(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3),
                rs.getString(4), switch (rs.getString(5)) { case "DENIED" -> "DENIED"; case "WAITING_APPROVAL" -> "NEEDS_APPROVAL"; default -> "ALLOWED"; },
                rs.getString(6) == null ? "" : rs.getString(6), rs.getString(7), json(rs.getString(8)), false), args.toArray());
        return page(rows, limit, BizDto.ToolCall::at, BizDto.ToolCall::id);
    }

    @Transactional(readOnly = true)
    public BizDto.Page<BizDto.AuditEntry> permissionAudit(String cursor, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, created_at, actor, action, detail::text FROM permission_audit
                WHERE action IN ('GRANT', 'UPDATE', 'REVOKE', 'KILL_SWITCH', 'ADMIN_FLAG')""");
        List<Object> args = new ArrayList<>();
        cursor(sql, args, cursor, "created_at", "id");
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit + 1);
        List<BizDto.AuditEntry> rows = jdbc.query(sql.toString(), (rs, i) -> {
            Map<String, Object> d = json(rs.getString(5));
            return new BizDto.AuditEntry(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getString(3), text(d.get("agentId")), text(d.get("toolId")),
                    rs.getString(4), asMap(d.get("before")), asMap(d.get("after")), text(d.get("reason")));
        }, args.toArray());
        return page(rows, limit, BizDto.AuditEntry::at, BizDto.AuditEntry::id);
    }

    private static void cursor(StringBuilder sql, List<Object> args, String cursor, String tsCol, String idCol) {
        if (cursor == null || cursor.isBlank()) return;
        try {
            String[] c = cursor.split("_", 2);
            sql.append(" AND (").append(tsCol).append(", ").append(idCol).append(") < (?, ?)");
            args.add(java.sql.Timestamp.from(Instant.ofEpochSecond(0, Long.parseLong(c[0]) * 1000)));
            args.add(UUID.fromString(c[1]));
        } catch (RuntimeException e) {
            throw new AppException(ErrorCode.VALIDATION, "cursor không hợp lệ");
        }
    }

    private static <T> BizDto.Page<T> page(List<T> rows, int limit, java.util.function.Function<T, Instant> at, java.util.function.Function<T, String> id) {
        if (rows.size() <= limit) return new BizDto.Page<>(rows, rows.size(), null);
        List<T> p = rows.subList(0, limit);
        Instant t = at.apply(p.get(limit - 1));
        return new BizDto.Page<>(p, p.size(), (t.getEpochSecond() * 1_000_000L + t.getNano() / 1000) + "_" + id.apply(p.get(limit - 1)));
    }

    // ================================================================== bí mật
    /** Chỉ liệt kê (tên, loại, nơi dùng). KHÔNG BAO GIỜ trả giá trị. Ghi/xoay giá trị cần SecretService (còn là phần "bạn tự viết"). */
    @Transactional(readOnly = true)
    public List<BizDto.SecretView> secrets() {
        Map<String, List<Map<String, String>>> used = new HashMap<>();
        jdbc.query("SELECT secret_id::text, agent_id::text, tool_id::text FROM agent_tool_grant WHERE secret_id IS NOT NULL",
                rs -> { used.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(Map.of("agentId", rs.getString(2), "toolId", rs.getString(3))); });
        return jdbc.query("SELECT id::text, name, kind, created_at FROM secret ORDER BY created_at",
                (rs, i) -> new BizDto.SecretView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), null, null,
                        used.getOrDefault(rs.getString(1), List.of())));
    }

    @Transactional
    public void deleteSecret(UUID id) {
        if (jdbc.update("DELETE FROM secret WHERE id = ?", id) == 0) throw new NotFoundException("secret", id);   // grant giữ lại, secret_id về NULL
        audit.record(OWNER, "UPDATE", Map.of("secretId", id, "after", Map.of("deleted", true)));
    }

    // ================================================================== helpers
    private List<ToolRow> toolRows() {
        return jdbc.query("SELECT id, name, description, risk_level, enabled, admin_flag_on FROM tool ORDER BY name",
                (rs, i) -> new ToolRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), RiskLevel.valueOf(rs.getString(4)), rs.getBoolean(5), rs.getBoolean(6)));
    }

    private ToolRow toolRow(UUID id) {
        return toolRows().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(() -> new NotFoundException("tool", id));
    }

    private Map<UUID, GrantRow> grantMap(UUID agentId) {
        Map<UUID, GrantRow> m = new LinkedHashMap<>();
        jdbc.query("SELECT agent_id, tool_id, scope::text, limits::text, always_approve, secret_id FROM agent_tool_grant WHERE agent_id = ? ORDER BY created_at, tool_id",
                rs -> { GrantRow g = grantRow(rs); m.put(g.toolId(), g); }, agentId);
        return m;
    }

    private GrantRow grantRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GrantRow(rs.getObject(2, UUID.class), json(rs.getString(3)), json(rs.getString(4)), rs.getBoolean(5), rs.getObject(6, UUID.class));
    }

    private void requireAgent(UUID id) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM agent WHERE id = ? AND archived_at IS NULL", Long.class, id);
        if (n == null || n == 0) throw new NotFoundException("agent", id);
    }

    private Map<String, Object> json(String s) {
        if (s == null || s.isBlank()) return Map.of();
        try { return mapper.readValue(s, new TypeReference<Map<String, Object>>() {}); } catch (Exception e) { return Map.of(); }
    }

    private String write(Object o) {
        try { return mapper.writeValueAsString(o); } catch (Exception e) { throw new AppException(ErrorCode.VALIDATION, "Không đọc được dữ liệu JSON"); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null; }

    private static String text(Object o) { return o == null ? null : String.valueOf(o); }

    private static UUID uuid(String s) {
        try { return UUID.fromString(s); } catch (RuntimeException e) { throw new AppException(ErrorCode.VALIDATION, "Id không hợp lệ: " + s); }
    }
}
