package com.agentoffice.service.fe;

import com.agentoffice.config.UsageProperties;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Dashboard tổng quan cho FE (types/stats.ts). Tính hoàn toàn từ bảng task + usage_daily; không bịa số: thiếu dữ liệu thì trả null/rỗng. */
@Service
@Transactional(readOnly = true)
public class StatsService {
    static final int MIN_FINISHED = 5;                 // dưới ngưỡng này FE hiện "chưa đủ dữ liệu" thay vì biểu đồ
    private static final long[] BUCKET_LIMITS = {10, 30, 120, 600};
    private static final String[] BUCKET_LABELS = {"< 10 giây", "10–30 giây", "30 giây – 2 phút", "2–10 phút", "> 10 phút"};

    private final JdbcTemplate jdbc;
    private final UsageProperties usageProps;

    public StatsService(JdbcTemplate jdbc, UsageProperties usageProps) {
        this.jdbc = jdbc;
        this.usageProps = usageProps;
    }

    public record Kpi(double value, Double prev) {}

    public record CostKpi(long value, Long prev) {}

    public record Day(String day, long completed, long failed, long costMicro) {}

    public record Bucket(String label, long count) {}

    public record DeptCost(String key, String label, long totalMicro) {}

    public record Busy(String agentId, long tasks, double utilization, long queueDepth, double errorRate, long costMicro) {}

    public record ErrorGroup(String key, String message, long count, List<String> agentIds, String lastTaskId, Instant lastAt) {}

    public record Overview(Instant updatedAt, String from, String to, boolean enoughData, Kpi completed, Kpi medianSec, Double p90Sec, Kpi errorRate,
                           Double recoveredRate, CostKpi cost, long running, long queued, long awaiting, List<Day> byDay, List<Bucket> durationBuckets,
                           List<DeptCost> costByDepartment, List<Busy> busiestAgents, List<ErrorGroup> errorGroups, List<List<Long>> heatmap) {}

    /** Khoảng ngày + bộ lọc dùng chung cho mọi truy vấn. */
    private record Scope(Timestamp start, Timestamp end, UUID departmentId, UUID projectId) {
        /** Điều kiện trên bảng task `t` (đã JOIN agent `a`). */
        String taskWhere() {
            return (departmentId != null ? " AND a.department_id = ?" : "") + (projectId != null ? " AND t.project_id = ?" : "");
        }

        List<Object> taskArgs(Object... lead) {
            List<Object> a = new ArrayList<>(List.of(lead));
            if (departmentId != null) a.add(departmentId);
            if (projectId != null) a.add(projectId);
            return a;
        }

        String usageWhere() {
            return (departmentId != null ? " AND department_id = ?" : "") + (projectId != null ? " AND project_id = ?" : "");
        }
    }

    public Overview overview(String from, String to, UUID departmentId, UUID projectId) {
        ZoneId zone = usageProps.reportZone();
        LocalDate today = LocalDate.now(zone);
        LocalDate f = day(from, today.minusDays(6));
        LocalDate t = day(to, today);
        if (t.isBefore(f)) throw new AppException(ErrorCode.VALIDATION, "to phải >= from");
        long len = ChronoUnit.DAYS.between(f, t) + 1;
        if (len > 366) throw new AppException(ErrorCode.VALIDATION, "Khoảng tối đa 366 ngày");
        Scope cur = new Scope(ts(f, zone), ts(t.plusDays(1), zone), departmentId, projectId);
        Scope prev = new Scope(ts(f.minusDays(len), zone), ts(f, zone), departmentId, projectId);

        long[] c = counts(cur);
        long[] p = counts(prev);
        boolean enough = c[0] + c[1] >= MIN_FINISHED;
        double[] dur = durations(cur);       // median, p90 (NaN = không có)
        double[] durPrev = durations(prev);

        Kpi completed = new Kpi(c[0], p[0] + p[1] == 0 ? null : (double) p[0]);
        Kpi median = new Kpi(nz(dur[0]), Double.isNaN(durPrev[0]) ? null : durPrev[0]);
        Kpi errRate = new Kpi(rate(c[1], c[0] + c[1]), p[0] + p[1] == 0 ? null : rate(p[1], p[0] + p[1]));
        long costNow = cost(cur);
        long costPrev = cost(prev);

        long[] live = live(departmentId, projectId);
        return new Overview(Instant.now(), f.toString(), t.toString(), enough, completed, median, Double.isNaN(dur[1]) ? null : dur[1], errRate,
                recovered(cur), new CostKpi(costNow, costPrev == 0 && p[0] + p[1] == 0 ? null : costPrev), live[0], live[1], live[2],
                byDay(cur, f, t, zone), buckets(cur), costByDepartment(cur), busiest(cur, len), errorGroups(cur), enough ? heatmap(cur, zone) : null);
    }

    // ------------------------------------------------------------------ KPI
    /** {thành công, thất bại} đã KẾT THÚC trong khoảng (theo finished_at). */
    private long[] counts(Scope s) {
        long[] r = new long[2];
        jdbc.query("""
                SELECT count(*) FILTER (WHERE t.status = 'SUCCEEDED'), count(*) FILTER (WHERE t.status = 'FAILED')
                FROM task t LEFT JOIN agent a ON a.id = t.assignee_agent_id
                WHERE t.status IN ('SUCCEEDED','FAILED') AND t.finished_at >= ? AND t.finished_at < ?""" + s.taskWhere(),
                rs -> { r[0] = rs.getLong(1); r[1] = rs.getLong(2); }, s.taskArgs(s.start(), s.end()).toArray());
        return r;
    }

    /** {trung vị, p90} giây của task thành công; NaN khi không có. */
    private double[] durations(Scope s) {
        double[] r = {Double.NaN, Double.NaN};
        jdbc.query("""
                SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY secs), percentile_cont(0.9) WITHIN GROUP (ORDER BY secs)
                FROM (SELECT extract(epoch FROM (t.finished_at - t.started_at)) AS secs
                      FROM task t LEFT JOIN agent a ON a.id = t.assignee_agent_id
                      WHERE t.status = 'SUCCEEDED' AND t.started_at IS NOT NULL AND t.finished_at >= ? AND t.finished_at < ?""" + s.taskWhere() + ") d",
                rs -> {
                    double m = rs.getDouble(1);
                    r[0] = rs.wasNull() ? Double.NaN : m;
                    double p = rs.getDouble(2);
                    r[1] = rs.wasNull() ? Double.NaN : p;
                }, s.taskArgs(s.start(), s.end()).toArray());
        return r;
    }

    /** Trong các task phải thử lại (attempts > 1) và đã kết thúc: tỷ lệ cuối cùng thành công. null nếu chưa có task nào thử lại. */
    private Double recovered(Scope s) {
        double[] r = {Double.NaN};
        jdbc.query("""
                SELECT count(*) FILTER (WHERE t.status = 'SUCCEEDED'), count(*)
                FROM task t LEFT JOIN agent a ON a.id = t.assignee_agent_id
                WHERE t.status IN ('SUCCEEDED','FAILED') AND t.attempts > 1 AND t.finished_at >= ? AND t.finished_at < ?""" + s.taskWhere(),
                rs -> { long total = rs.getLong(2); if (total > 0) r[0] = (double) rs.getLong(1) / total; }, s.taskArgs(s.start(), s.end()).toArray());
        return Double.isNaN(r[0]) ? null : r[0];
    }

    private long cost(Scope s) {
        List<Object> a = new ArrayList<>(List.of(date(s.start()), date(s.end())));
        if (s.departmentId() != null) a.add(s.departmentId());
        if (s.projectId() != null) a.add(s.projectId());
        Long v = jdbc.queryForObject("SELECT coalesce(sum(cost_micro_usd), 0) FROM usage_daily WHERE day >= ? AND day < ?" + s.usageWhere(), Long.class, a.toArray());
        return v == null ? 0 : v;
    }

    /** {đang chạy, đang chờ, chờ duyệt} HIỆN TẠI (không phụ thuộc khoảng ngày). */
    private long[] live(UUID departmentId, UUID projectId) {
        long[] r = new long[3];
        Scope s = new Scope(null, null, departmentId, projectId);
        jdbc.query("""
                SELECT count(*) FILTER (WHERE t.status = 'RUNNING'),
                       count(*) FILTER (WHERE t.status IN ('PENDING','BLOCKED','QUEUED','WAITING_BUDGET')),
                       count(*) FILTER (WHERE t.status = 'WAITING_APPROVAL')
                FROM task t LEFT JOIN agent a ON a.id = t.assignee_agent_id WHERE true""" + s.taskWhere(),
                rs -> { r[0] = rs.getLong(1); r[1] = rs.getLong(2); r[2] = rs.getLong(3); }, s.taskArgs().toArray());
        return r;
    }

    // ------------------------------------------------------------------ biểu đồ
    private List<Day> byDay(Scope s, LocalDate f, LocalDate t, ZoneId zone) {
        Map<String, long[]> days = new LinkedHashMap<>();
        for (LocalDate d = f; !d.isAfter(t); d = d.plusDays(1)) days.put(d.toString(), new long[3]);
        jdbc.query("""
                SELECT (t.finished_at AT TIME ZONE ?)::date, count(*) FILTER (WHERE t.status = 'SUCCEEDED'), count(*) FILTER (WHERE t.status = 'FAILED')
                FROM task t LEFT JOIN agent a ON a.id = t.assignee_agent_id
                WHERE t.status IN ('SUCCEEDED','FAILED') AND t.finished_at >= ? AND t.finished_at < ?""" + s.taskWhere() + " GROUP BY 1",
                rs -> { long[] v = days.get(rs.getDate(1).toLocalDate().toString()); if (v != null) { v[0] = rs.getLong(2); v[1] = rs.getLong(3); } },
                s.taskArgs(zone.getId(), s.start(), s.end()).toArray());
        List<Object> a = new ArrayList<>(List.of(date(s.start()), date(s.end())));
        if (s.departmentId() != null) a.add(s.departmentId());
        if (s.projectId() != null) a.add(s.projectId());
        jdbc.query("SELECT day, sum(cost_micro_usd) FROM usage_daily WHERE day >= ? AND day < ?" + s.usageWhere() + " GROUP BY day",
                rs -> { long[] v = days.get(rs.getDate(1).toLocalDate().toString()); if (v != null) v[2] = rs.getLong(2); }, a.toArray());
        return days.entrySet().stream().map(e -> new Day(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2])).toList();
    }

    private List<Bucket> buckets(Scope s) {
        long[] counts = new long[BUCKET_LABELS.length];
        jdbc.query("""
                SELECT extract(epoch FROM (t.finished_at - t.started_at))
                FROM task t LEFT JOIN agent a ON a.id = t.assignee_agent_id
                WHERE t.status = 'SUCCEEDED' AND t.started_at IS NOT NULL AND t.finished_at >= ? AND t.finished_at < ?""" + s.taskWhere(),
                rs -> {
                    double secs = rs.getDouble(1);
                    int i = 0;
                    while (i < BUCKET_LIMITS.length && secs >= BUCKET_LIMITS[i]) i++;
                    counts[i]++;
                }, s.taskArgs(s.start(), s.end()).toArray());
        List<Bucket> out = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) out.add(new Bucket(BUCKET_LABELS[i], counts[i]));
        return out;
    }

    private List<DeptCost> costByDepartment(Scope s) {
        List<Object> a = new ArrayList<>(List.of(date(s.start()), date(s.end())));
        if (s.departmentId() != null) a.add(s.departmentId());
        if (s.projectId() != null) a.add(s.projectId());
        return jdbc.query("""
                SELECT coalesce(u.department_id::text, 'none'), coalesce(d.name, 'Không xác định'), sum(u.cost_micro_usd)
                FROM usage_daily u LEFT JOIN department d ON d.id = u.department_id
                WHERE u.day >= ? AND u.day < ?""" + s.usageWhere().replace(" AND department_id", " AND u.department_id").replace(" AND project_id", " AND u.project_id")
                + " GROUP BY 1, 2 HAVING sum(u.cost_micro_usd) > 0 ORDER BY 3 DESC",
                (rs, i) -> new DeptCost(rs.getString(1), rs.getString(2), rs.getLong(3)), a.toArray());
    }

    private List<Busy> busiest(Scope s, long days) {
        double windowSecs = Math.max(1, days * 86_400.0);
        List<Object> args = new ArrayList<>(List.of(s.start(), s.end()));
        args.addAll(s.taskArgs());
        List<Busy> rows = jdbc.query("""
                SELECT t.assignee_agent_id, count(*), coalesce(sum(extract(epoch FROM (t.finished_at - t.started_at))) FILTER (WHERE t.started_at IS NOT NULL), 0),
                       count(*) FILTER (WHERE t.status = 'FAILED')
                FROM task t LEFT JOIN agent a ON a.id = t.assignee_agent_id
                WHERE t.assignee_agent_id IS NOT NULL AND t.status IN ('SUCCEEDED','FAILED') AND t.finished_at >= ? AND t.finished_at < ?""" + s.taskWhere()
                + " GROUP BY t.assignee_agent_id ORDER BY count(*) DESC LIMIT 8",
                (rs, i) -> new Busy(rs.getString(1), rs.getLong(2), Math.min(1.0, rs.getDouble(3) / windowSecs), 0, rate(rs.getLong(4), rs.getLong(2)), 0), args.toArray());
        if (rows.isEmpty()) return rows;
        Map<String, Long> queue = new LinkedHashMap<>();
        jdbc.query("SELECT assignee_agent_id::text, count(*) FROM task WHERE status IN ('PENDING','BLOCKED','QUEUED','WAITING_BUDGET') AND assignee_agent_id IS NOT NULL GROUP BY 1",
                rs -> { queue.put(rs.getString(1), rs.getLong(2)); });
        Map<String, Long> cost = new LinkedHashMap<>();
        jdbc.query("SELECT agent_id::text, sum(cost_micro_usd) FROM usage_daily WHERE day >= ? AND day < ? GROUP BY 1",
                rs -> { cost.put(rs.getString(1), rs.getLong(2)); }, date(s.start()), date(s.end()));
        return rows.stream().map(b -> new Busy(b.agentId(), b.tasks(), b.utilization(), queue.getOrDefault(b.agentId(), 0L), b.errorRate(),
                cost.getOrDefault(b.agentId(), 0L))).toList();
    }

    private List<ErrorGroup> errorGroups(Scope s) {
        return jdbc.query("""
                SELECT md5(coalesce(left(t.error, 120), '')), coalesce(left(t.error, 120), '(không có thông báo lỗi)'), count(*),
                       array_agg(DISTINCT t.assignee_agent_id::text) FILTER (WHERE t.assignee_agent_id IS NOT NULL),
                       (array_agg(t.id::text ORDER BY t.finished_at DESC))[1], max(t.finished_at)
                FROM task t LEFT JOIN agent a ON a.id = t.assignee_agent_id
                WHERE t.status = 'FAILED' AND t.finished_at >= ? AND t.finished_at < ?""" + s.taskWhere()
                + " GROUP BY 1, 2 ORDER BY count(*) DESC, max(t.finished_at) DESC LIMIT 10",
                (rs, i) -> {
                    java.sql.Array arr = rs.getArray(4);
                    List<String> agents = new ArrayList<>();
                    if (arr != null) for (Object o : (Object[]) arr.getArray()) agents.add(String.valueOf(o));
                    return new ErrorGroup(rs.getString(1), rs.getString(2), rs.getLong(3), agents, rs.getString(5), rs.getTimestamp(6).toInstant());
                }, s.taskArgs(s.start(), s.end()).toArray());
    }

    /** 7 hàng (thứ 2..CN) x 24 cột (giờ): số task kết thúc theo giờ địa phương. */
    private List<List<Long>> heatmap(Scope s, ZoneId zone) {
        long[][] grid = new long[7][24];
        jdbc.query("""
                SELECT extract(isodow FROM t.finished_at AT TIME ZONE ?)::int, extract(hour FROM t.finished_at AT TIME ZONE ?)::int, count(*)
                FROM task t LEFT JOIN agent a ON a.id = t.assignee_agent_id
                WHERE t.status IN ('SUCCEEDED','FAILED') AND t.finished_at >= ? AND t.finished_at < ?""" + s.taskWhere() + " GROUP BY 1, 2",
                rs -> { grid[rs.getInt(1) - 1][rs.getInt(2)] = rs.getLong(3); }, s.taskArgs(zone.getId(), zone.getId(), s.start(), s.end()).toArray());
        List<List<Long>> out = new ArrayList<>();
        for (long[] row : grid) {
            List<Long> r = new ArrayList<>();
            for (long v : row) r.add(v);
            out.add(r);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers
    private static double rate(long part, long whole) { return whole == 0 ? 0 : (double) part / whole; }

    private static double nz(double v) { return Double.isNaN(v) ? 0 : v; }

    private static Timestamp ts(LocalDate d, ZoneId zone) { return Timestamp.from(d.atStartOfDay(zone).toInstant()); }

    /** usage_daily lưu theo NGÀY báo cáo: đổi mốc thời gian về ngày cùng múi giờ. */
    private java.sql.Date date(Timestamp t) {
        return java.sql.Date.valueOf(t.toInstant().atZone(usageProps.reportZone()).toLocalDate());
    }

    private static LocalDate day(String s, LocalDate dflt) {
        if (s == null || s.isBlank()) return dflt;
        try { return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s); }
        catch (RuntimeException e) { throw new AppException(ErrorCode.VALIDATION, "Ngày không hợp lệ: " + s); }
    }
}
