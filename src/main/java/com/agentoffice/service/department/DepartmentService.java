package com.agentoffice.service.department;

import com.agentoffice.dto.fe.OfficeDto;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.service.agent.AgentService;
import com.agentoffice.util.Slugs;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tạo / sửa / xoá phòng ban theo hợp đồng FE (docs/MISSING_API.md A1b). Tên phòng là khoá nối với agent.department
 * và agent_template.department, nên đổi tên phải đổi cả hai chỗ đó trong cùng giao dịch.
 */
@Service
public class DepartmentService {
    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
    private static final Pattern PROP = Pattern.compile("^[a-z0-9-]{1,40}$");
    private static final Set<String> RESERVED = Set.of("khu-chung", "chua-phan-phong");
    /** Phòng gắn với khu thiết bị bên FE: không xoá được. */
    static final String INFRA_SLUG = "ha-tang";

    private final JdbcTemplate jdbc;
    private final AgentService agents;

    public DepartmentService(JdbcTemplate jdbc, AgentService agents) {
        this.jdbc = jdbc;
        this.agents = agents;
    }

    @Transactional
    public UUID create(OfficeDto.DepartmentInput in) {
        Clean c = clean(in);
        if (exists("name", c.name, null)) throw new AppException(ErrorCode.CONFLICT, "Đã có phòng tên \"" + c.name + "\"");
        String slug = in.slug() != null && !in.slug().isBlank() ? requestedSlug(in.slug()) : freeSlug(c.name);
        int order = jdbc.queryForObject("SELECT coalesce(max(sort_order), 0) + 1 FROM department", Integer.class);
        UUID id = jdbc.queryForObject("""
                INSERT INTO department(name, slug, icon, color, props, monitors_per_desk, sort_order)
                VALUES (?, ?, ?, ?, ?::text[], ?, ?) RETURNING id""", UUID.class,
                c.name, slug, c.icon, c.color, array(c.props), c.monitors, order);
        createHead(id, c, in);
        return id;
    }

    /** slug KHÔNG đổi để URL cũ không gãy. Đổi tên thì cập nhật cả agent và mẫu agent đang trỏ theo tên. */
    @Transactional
    public void update(UUID id, OfficeDto.DepartmentInput in) {
        String oldName = nameOf(id);
        Clean c = clean(in);
        if (!c.name.equals(oldName)) {
            if (exists("name", c.name, id)) throw new AppException(ErrorCode.CONFLICT, "Đã có phòng tên \"" + c.name + "\"");
            jdbc.update("UPDATE agent SET department = ? WHERE department_id = ?", c.name, id);
            jdbc.update("UPDATE agent_template SET department = ? WHERE department = ?", c.name, oldName);
        }
        jdbc.update("UPDATE department SET name = ?, icon = ?, color = ?, props = ?::text[], monitors_per_desk = ? WHERE id = ?",
                c.name, c.icon, c.color, array(c.props), c.monitors, id);
        createHead(id, c, in);
    }

    /** Có headName và phòng chưa có trưởng phòng thì tạo agent trưởng phòng (kèm bot Hermes nếu máy Hermes hỗ trợ). */
    private void createHead(UUID deptId, Clean c, OfficeDto.DepartmentInput in) {
        if (in.headName() == null || in.headName().isBlank()) return;
        Long has = jdbc.queryForObject("SELECT count(*) FROM department WHERE id = ? AND head_agent_id IS NOT NULL", Long.class, deptId);
        if (has != null && has > 0) return;
        String role = in.headRole() == null || in.headRole().isBlank() ? "Trưởng phòng " + c.name : in.headRole().trim();
        UUID head = agents.create(in.headName().trim(), role, c.name, new AgentService.Profile(null, null, c.color, null)).getId();
        jdbc.update("UPDATE department SET head_agent_id = ? WHERE id = ?", head, deptId);
    }

    /** Agent trong phòng không bị xoá: về "chưa phân phòng". */
    @Transactional
    public void delete(UUID id) {
        String name = nameOf(id);
        String slug = jdbc.queryForObject("SELECT slug FROM department WHERE id = ?", String.class, id);
        if (INFRA_SLUG.equals(slug))
            throw new AppException(ErrorCode.CONFLICT, "Phòng Hạ tầng gắn với khu thiết bị nên không xoá được");
        long templates = jdbc.queryForObject("SELECT count(*) FROM agent_template WHERE department = ?", Long.class, name);
        if (templates > 0) throw new AppException(ErrorCode.CONFLICT, "Phòng còn " + templates + " mẫu agent đang dùng tên này");
        try {
            jdbc.update("UPDATE agent SET department_id = NULL WHERE department_id = ?", id);
            jdbc.update("UPDATE goal SET department_id = NULL WHERE department_id = ?", id);
            jdbc.update("DELETE FROM department WHERE id = ?", id);
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.CONFLICT, "Phòng còn dữ liệu liên quan (ngân sách, mục tiêu...) nên chưa xoá được", null, e);
        }
    }

    // ------------------------------------------------------------------ helpers
    private record Clean(String name, String icon, String color, List<String> props, int monitors) {}

    private static Clean clean(OfficeDto.DepartmentInput in) {
        String name = in.name() == null ? "" : in.name().trim();
        if (name.length() < 2 || name.length() > 40) throw new AppException(ErrorCode.VALIDATION, "Tên phòng từ 2 đến 40 ký tự");
        if (in.color() == null || !COLOR.matcher(in.color()).matches()) throw new AppException(ErrorCode.VALIDATION, "Màu phải dạng #rrggbb");
        Set<String> props = new LinkedHashSet<>();
        if (in.props() != null) for (String p : in.props()) if (p != null && PROP.matcher(p).matches()) props.add(p);
        int monitors = in.monitorsPerDesk() == null ? 1 : Math.min(3, Math.max(1, in.monitorsPerDesk()));
        String icon = in.icon() == null || in.icon().isBlank() ? "building" : in.icon().trim();
        if (icon.length() > 40) throw new AppException(ErrorCode.VALIDATION, "Tên icon quá dài");
        return new Clean(name, icon, in.color().toLowerCase(), List.copyOf(props), monitors);
    }

    /** Slug do client chỉ định (FE nhận biết phòng theo slug, vd "ha-tang"): phải hợp lệ và chưa bị dùng. */
    private String requestedSlug(String slug) {
        String s = slug.trim();
        if (!Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$").matcher(s).matches() || s.length() > 50 || RESERVED.contains(s))
            throw new AppException(ErrorCode.VALIDATION, "Slug không hợp lệ: " + slug);
        if (exists("slug", s, null)) throw new AppException(ErrorCode.CONFLICT, "Slug \"" + s + "\" đã được dùng");
        return s;
    }

    private String freeSlug(String name) {
        String base = Slugs.of(name, "phong-ban");
        String slug = base;
        for (int i = 2; RESERVED.contains(slug) || exists("slug", slug, null); i++) slug = base + "-" + i;
        return slug;
    }

    private boolean exists(String column, String value, UUID except) {
        String sql = "SELECT count(*) FROM department WHERE " + column + " = ?" + (except == null ? "" : " AND id <> ?");
        Long n = except == null ? jdbc.queryForObject(sql, Long.class, value) : jdbc.queryForObject(sql, Long.class, value, except);
        return n != null && n > 0;
    }

    private String nameOf(UUID id) {
        List<String> n = jdbc.queryForList("SELECT name FROM department WHERE id = ?", String.class, id);
        if (n.isEmpty()) throw new NotFoundException("department", id);
        return n.get(0);
    }

    /** Dạng literal mảng Postgres, truyền qua ?::text[] (tham số bind, không nối vào SQL). */
    private static String array(List<String> items) {
        return "{" + String.join(",", items) + "}";
    }
}
