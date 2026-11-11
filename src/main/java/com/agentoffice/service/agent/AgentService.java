package com.agentoffice.service.agent;

import com.agentoffice.client.hermes.HermesBotClient;
import com.agentoffice.config.HermesAdminProperties;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.repository.agent.AgentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tạo agent kèm bot tương ứng trên Hermes. Hermes admin chưa cấu hình thì chỉ tạo agent (hermesProfile = null).
 * Không gọi Hermes trong giao dịch DB: một lần gọi có thể tới 150 giây và không rollback được. Thứ tự từng lệnh:
 * tạo = lưu DB rồi tạo bot (lỗi thì agent NOT_SYNCED, POST /hermes/sync-all tạo lại);
 * sửa = ghi sang bot rồi lưu DB (lỗi thì chưa đổi gì);
 * xoá = lưu DB rồi xoá bot (lỗi thì còn bot thừa, hiện ở orphans, chứ không mất bộ nhớ bot).
 */
@Service
public class AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AgentRepository repo;
    private final HermesBotClient hermes;
    private final HermesAdminProperties props;
    private final ObjectMapper mapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.agentoffice.config.LocalLlmProperties local;
    private final String hermesLlmBaseUrl;
    private final TransactionTemplate tx;
    private final com.agentoffice.service.studio.PromptVersionService versions;

    public AgentService(AgentRepository repo, HermesBotClient hermes, HermesAdminProperties props, ObjectMapper mapper,
                        org.springframework.jdbc.core.JdbcTemplate jdbc, com.agentoffice.config.LocalLlmProperties local,
                        TransactionTemplate tx, com.agentoffice.service.studio.PromptVersionService versions,
                        @org.springframework.beans.factory.annotation.Value("${agentoffice.hermes-llm.base-url:}") String hermesLlmBaseUrl) {
        this.tx = tx;
        this.versions = versions;
        this.local = local;
        this.hermesLlmBaseUrl = hermesLlmBaseUrl == null ? "" : hermesLlmBaseUrl.trim().replaceAll("/+$", "");
        this.jdbc = jdbc;
        this.repo = repo;
        this.hermes = hermes;
        this.props = props;
        this.mapper = mapper;
    }

    /** Phần cấu hình hiển thị của agent; trường null = không đổi / để mặc định. */
    public record Profile(String systemPrompt, String model, String color, java.util.List<String> skills) {}

    /** Những gì hồ sơ agent quyết định trên bot; model = null nghĩa là giữ model của bot. */
    private record BotFields(String description, String soul, String model, String provider, String baseUrl, Integer contextLength) {}

    private record BotModel(String model, String provider, String baseUrl, Integer contextLength) {}

    public Agent create(String name, String role, String department) { return create(name, role, department, null); }

    public Agent create(String name, String role, String department, Profile profile) {
        requireBotModel(profile == null ? null : profile.model(), null);
        Agent agent = tx.execute(s -> {
            Agent a = new Agent(name, role, department);
            apply(a, profile);
            a = repo.saveAndFlush(a);
            versions.record(a.getId(), a.getSystemPrompt(), "Bản khởi tạo", false);
            return a;
        });
        if (!hermes.configured()) return agent;
        String botName = profileName(name, agent);
        try {
            createBot(botName, agent);
        } catch (AppException e) {
            log.warn("Tạo agent '{}' mà chưa có bot Hermes: {}", name, e.getMessage());
            return agent;
        }
        return link(agent.getId(), botName);
    }

    /**
     * Sửa agent. department = null thì giữ phòng cũ; đổi phòng thì cập nhật cả department_id.
     * Chỉ ghi sang bot những gì thực sự đổi, nên đổi màu không đụng tới bot. Hermes lỗi thì ném lỗi trước khi lưu, DB và Hermes không lệch.
     * Gateway của bot cần restart thì model/SOUL mới có hiệu lực.
     */
    public Agent update(UUID id, String name, String role, String department, Profile profile) {
        return update(id, name, role, department, profile, null);
    }

    /**
     * promptNote != null: luôn ghi một phiên bản prompt với ghi chú này (Studio lưu / khôi phục / áp mẫu).
     * promptNote = null: chỉ ghi phiên bản khi prompt thực sự đổi (sửa trong hồ sơ agent).
     */
    public Agent update(UUID id, String name, String role, String department, Profile profile, String promptNote) {
        Agent old = active(id);
        requireBotModel(profile == null ? null : profile.model(), old.getModelProfile());
        String newName = given(name, old.getName());
        String newRole = given(role, old.getRole());
        String newDepartment = given(department, old.getDepartment());
        String prompt = profile != null && profile.systemPrompt() != null ? profile.systemPrompt() : old.getSystemPrompt();
        String model = given(profile == null ? null : profile.model(), old.getModelProfile());
        pushToBot(old, fields(newName, newRole, newDepartment, prompt, model));
        return tx.execute(s -> {
            Agent a = active(id);
            a.setName(newName);
            a.setRole(newRole);
            if (!Objects.equals(newDepartment, a.getDepartment())) {
                a.setDepartment(newDepartment);
                jdbc.update("INSERT INTO department(name) VALUES (?) ON CONFLICT (name) DO NOTHING", newDepartment);
                jdbc.update("UPDATE agent SET department_id = (SELECT id FROM department WHERE name = ?) WHERE id = ?", newDepartment, id);
            }
            apply(a, profile);
            if (promptNote != null || !Objects.equals(prompt, old.getSystemPrompt()))
                versions.record(id, prompt, promptNote != null ? promptNote : "Sửa trong hồ sơ agent", promptNote != null);
            return repo.save(a);
        });
    }

    /** Model vừa sửa thẳng trên bot: ghi vào hồ sơ, để lần lưu hồ sơ sau không ghi đè lại model cũ. */
    public void recordBotModel(UUID id, String model) {
        tx.executeWithoutResult(s -> {
            Agent a = active(id);
            a.setModelProfile(model);
            repo.save(a);
        });
    }

    private Agent active(UUID id) {
        return repo.findById(id).filter(x -> x.getArchivedAt() == null).orElseThrow(() -> new NotFoundException("agent", id));
    }

    private static String given(String value, String current) { return value != null && !value.isBlank() ? value : current; }

    /** Model mới mà bot không chạy được thì từ chối, thay vì để hồ sơ một đằng bot một nẻo. Giá trị cũ giữ nguyên thì vẫn cho lưu. */
    private void requireBotModel(String model, String current) {
        if (model == null || model.isBlank() || model.equals(current) || !hermes.configured() || botModel(model) != null) return;
        throw new AppException(ErrorCode.VALIDATION, "Model '" + model + "' không chạy được trên bot Hermes, hãy chọn model của Ollama");
    }

    private BotFields fields(String name, String role, String department, String systemPrompt, String modelProfile) {
        BotModel route = botRoute(modelProfile);
        return new BotFields(role + " - " + department, soul(name, role, department, systemPrompt),
                route == null ? null : route.model(), route == null ? null : route.provider(),
                route == null ? null : route.baseUrl(), route == null ? null : route.contextLength());
    }

    private BotFields fields(Agent a) {
        return fields(a.getName(), a.getRole(), a.getDepartment(), a.getSystemPrompt(), a.getModelProfile());
    }

    /** Bot đã mất bên Hermes thì bỏ qua (so khớp sẽ báo MISSING); lỗi khác ném ra. */
    private void pushToBot(Agent old, BotFields next) {
        if (old.getHermesProfile() == null || !hermes.configured()) return;
        BotFields current = fields(old);
        ObjectNode body = mapper.createObjectNode();
        if (!next.description().equals(current.description())) body.put("description", next.description());
        if (!next.soul().equals(current.soul())) body.put("soul", next.soul());
        if (next.model() != null && !next.model().equals(current.model())) body.put("model", next.model());
        if (next.provider() != null && !next.provider().equals(current.provider())) body.put("provider", next.provider());
        if (next.baseUrl() != null && !next.baseUrl().equals(current.baseUrl())) body.put("baseUrl", next.baseUrl());
        if (next.contextLength() != null && !next.contextLength().equals(current.contextLength())) body.put("contextLength", next.contextLength());
        if (body.isEmpty()) return;
        try {
            hermes.update(old.getHermesProfile(), body);
        } catch (NotFoundException missing) {
            log.warn("Bot '{}' của agent '{}' không còn trên Hermes", old.getHermesProfile(), old.getName());
        }
    }

    /**
     * "Xoá" agent = lưu trữ: tắt hẳn (OFFLINE, không nhận task), ẩn khỏi FE, gỡ chức trưởng phòng, rồi xoá bot trên Hermes.
     * Không xoá dòng trong DB vì task/approval/chi phí cũ còn tham chiếu. Trả tên bot đã xoá (null nếu agent không có bot).
     */
    public String archive(UUID id) {
        String bot = tx.execute(s -> {
            Agent a = active(id);
            String profile = a.getHermesProfile();
            jdbc.update("UPDATE department SET head_agent_id = NULL WHERE head_agent_id = ?", id);
            a.setHermesProfile(null);
            a.setStatus("OFFLINE");
            a.setArchivedAt(java.time.Instant.now());
            repo.save(a);
            return profile;
        });
        if (bot != null && hermes.configured()) {
            try {
                hermes.delete(bot);
            } catch (NotFoundException alreadyGone) {
                // bot đã mất bên Hermes: coi như đã xoá
            } catch (AppException e) {
                log.warn("Agent đã lưu trữ nhưng chưa xoá được bot '{}': {}", bot, e.getMessage());
            }
        }
        return bot;
    }

    private static void apply(Agent a, Profile p) {
        if (p == null) return;
        if (p.systemPrompt() != null) a.setSystemPrompt(p.systemPrompt());
        if (p.model() != null && !p.model().isBlank()) a.setModelProfile(p.model());
        if (p.color() != null && !p.color().isBlank()) a.setColor(p.color());
        if (p.skills() != null) a.setSkills(new java.util.ArrayList<>(p.skills()));
    }

    /**
     * Bảo đảm agent có bot sống trên Hermes. Trả true nếu vừa tạo bot mới (agent chưa có profile, hoặc bot đã mất).
     * Hermes lỗi thì ném lỗi, DB không đổi.
     */
    public boolean ensureBot(UUID agentId) {
        Agent a = active(agentId);
        if (a.getHermesProfile() != null) {
            try {
                hermes.get(a.getHermesProfile());
                return false;
            } catch (NotFoundException missing) {
                // bot đã bị xoá bên Hermes: tạo lại cùng tên bên dưới
            }
        }
        String profile = a.getHermesProfile() != null ? a.getHermesProfile() : profileName(a.getName(), a);
        createBot(profile, a);
        link(agentId, profile);
        return true;
    }

    private void createBot(String profile, Agent a) {
        try {
            hermes.create(botBody(profile, a));
        } catch (NotFoundException e) {
            throw new AppException(ErrorCode.HERMES_NOT_CONFIGURED, "Bot mẫu '" + props.cloneFrom() + "' không còn trên Hermes, kiểm tra HERMES_CLONE_FROM", null, e);
        } catch (AppException e) {
            if (e.code() != ErrorCode.CONFLICT) throw e;
            // tên bot gắn với id agent: đã có nghĩa là lần trước Hermes tạo xong mà BE không nhận được phản hồi, nên nhận lại
            log.warn("Bot '{}' đã có sẵn trên Hermes, nhận lại cho agent '{}'", profile, a.getName());
            hermes.update(profile, config(a));
        }
    }

    private Agent link(UUID id, String profile) {
        return tx.execute(s -> {
            Agent a = active(id);
            a.setHermesProfile(profile);
            return repo.save(a);
        });
    }

    /** Tên profile: slug không dấu của tên agent + 8 ký tự đầu của id, nên không bao giờ trùng và luôn hợp lệ ([A-Za-z0-9_-], ≤ 64). */
    static String profileName(String agentName, Agent agent) {
        String slug = Normalizer.normalize(agentName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = "agent";
        if (slug.length() > 40) slug = slug.substring(0, 40).replaceAll("-+$", "");
        return slug + "-" + agent.getId().toString().substring(0, 8);
    }

    private ObjectNode botBody(String profile, Agent a) {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", profile);
        if (!props.cloneFrom().isBlank()) body.put("cloneFrom", props.cloneFrom().trim());
        return body.setAll(config(a));
    }

    private ObjectNode config(Agent a) {
        BotFields f = fields(a);
        ObjectNode body = mapper.createObjectNode();
        body.put("description", f.description());
        body.put("soul", f.soul());
        if (f.model() != null) body.put("model", f.model());
        if (f.provider() != null) body.put("provider", f.provider());
        if (f.baseUrl() != null) body.put("baseUrl", f.baseUrl());
        if (f.contextLength() != null) body.put("contextLength", f.contextLength());
        return body;
    }

    /** System prompt người dùng viết đã đủ nên dùng nguyên văn; chưa có thì tự dựng câu giới thiệu. */
    static String soul(String name, String role, String department, String systemPrompt) {
        if (systemPrompt != null && !systemPrompt.isBlank()) return systemPrompt;
        return "Bạn là " + name + ", vai trò " + role + ", thuộc phòng " + department + ".";
    }

    /**
     * Model trên Ollama mà bot sẽ dùng, suy từ model_profile của agent: tên bí danh (local.small...) -> model thật;
     * tên đã là model thật (có ':' hoặc '/', vd qwen3.6:35b...) -> dùng nguyên; hồ sơ model LOCAL (thêm ở trang Model) -> cột model;
     * tên khác (vd cloud.*, claude-*, không chạy được trên Ollama) -> null. Tạo/sửa agent từ chối giá trị này (requireBotModel),
     * chỉ agent cũ còn giữ thì bot giữ nguyên model.
     */
    String botModel(String modelProfile) {
        BotModel route = botRoute(modelProfile);
        return route == null ? null : route.model();
    }

    private BotModel botRoute(String modelProfile) {
        if (modelProfile == null || modelProfile.isBlank()) return null;
        String alias = local.modelAliases() == null ? null : local.modelAliases().get(modelProfile);
        if (alias != null && !alias.isBlank()) return new BotModel(alias, null, null, null);
        if (modelProfile.contains(":") || modelProfile.contains("/")) return new BotModel(modelProfile, null, null, null);
        if (jdbc == null) return null;
        return jdbc.query("SELECT model, is_local, context_tokens FROM model_profile WHERE name = ?", rs -> {
            if (!rs.next()) return null;
            if (rs.getBoolean("is_local")) return new BotModel(rs.getString("model"), null, null, null);
            if (hermesLlmBaseUrl.isBlank()) return null;
            return new BotModel(rs.getString("model"), "custom", hermesLlmBaseUrl, rs.getInt("context_tokens"));
        }, modelProfile);
    }
}
