package com.agentoffice.service.hermes;

import com.agentoffice.client.hermes.HermesBotClient;
import com.agentoffice.config.HermesAdminProperties;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.exception.NotFoundException;
import com.agentoffice.repository.agent.AgentRepository;
import com.agentoffice.service.agent.AgentService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * So khớp agent trong AgentOffice với bot (profile) trên Hermes theo hợp đồng FE (types/hermes.ts).
 * Một agent "đồng bộ" khi agent.hermesProfile trỏ tới bot đang có trong Hermes.
 */
@Service
public class HermesSyncService {
    private static final Logger log = LoggerFactory.getLogger(HermesSyncService.class);
    /** Hồ sơ agent quyết định các trường này; sửa thẳng trên bot sẽ lệch và bị ghi đè. */
    private static final Set<String> OWNED_BY_AGENT = Set.of("model", "soul", "description");

    public record Status(boolean reachable, Instant checkedAt, String detail, int agentsInOffice,
                         Integer botsInHermes, int synced, int missing, int orphans) {}

    public record AgentSync(String agentId, String name, String hermesBotId, String syncState,
                            String error, Instant lastSyncedAt) {}

    public record Failed(String agentId, String error) {}

    public record SyncAll(int created, int updated, List<Failed> failed) {}

    private final HermesBotClient hermes;
    private final AgentRepository agents;
    private final AgentService agentService;
    private final HermesAdminProperties props;

    public HermesSyncService(HermesBotClient hermes, AgentRepository agents, AgentService agentService, HermesAdminProperties props) {
        this.props = props;
        this.hermes = hermes;
        this.agents = agents;
        this.agentService = agentService;
    }

    public Status status() {
        List<Agent> all = agents.findByArchivedAtIsNull(org.springframework.data.domain.Sort.by("createdAt"));
        Instant now = Instant.now();
        Set<String> bots;
        try {
            bots = botNames();
        } catch (AppException e) {
            return new Status(false, now, e.getMessage(), all.size(), null, 0, 0, 0);
        }
        int synced = 0, missing = 0;
        Set<String> linked = new HashSet<>();
        for (Agent a : all) {
            String p = a.getHermesProfile();
            if (p == null) { missing++; continue; }
            linked.add(p);
            if (bots.contains(p)) synced++; else missing++;
        }
        int orphans = (int) bots.stream().filter(b -> !linked.contains(b)).count();
        return new Status(true, now, null, all.size(), bots.size(), synced, missing, orphans);
    }

    public List<AgentSync> list() {
        Set<String> bots = null;
        String error = null;
        try {
            bots = botNames();
        } catch (AppException e) {
            error = e.getMessage();
        }
        List<AgentSync> out = new ArrayList<>();
        for (Agent a : agents.findByArchivedAtIsNull(org.springframework.data.domain.Sort.by("createdAt"))) out.add(view(a, bots, error));
        return out;
    }

    /** Tạo bot cho agent chưa có (hoặc bot đã mất). Bot đang sống thì giữ nguyên. */
    public AgentSync sync(UUID agentId) {
        Agent a = agents.findById(agentId).orElseThrow(() -> new NotFoundException("agent", agentId));
        try {
            agentService.ensureBot(a.getId());
        } catch (AppException e) {
            return new AgentSync(a.getId().toString(), a.getName(), a.getHermesProfile(), "ERROR", e.getMessage(), null);
        }
        return one(agentId);
    }

    public AgentSync restart(UUID agentId) {
        Agent a = agents.findById(agentId).orElseThrow(() -> new NotFoundException("agent", agentId));
        if (a.getHermesProfile() == null) {
            return new AgentSync(a.getId().toString(), a.getName(), null, "NOT_SYNCED", "Agent chưa có bot trên Hermes", null);
        }
        try {
            hermes.restart(a.getHermesProfile());
        } catch (AppException e) {
            return new AgentSync(a.getId().toString(), a.getName(), a.getHermesProfile(), "ERROR", e.getMessage(), null);
        }
        return one(agentId);
    }

    public SyncAll syncAll() {
        int created = 0, updated = 0;
        List<Failed> failed = new ArrayList<>();
        for (Agent a : agents.findByArchivedAtIsNull(org.springframework.data.domain.Sort.by("createdAt"))) {
            boolean had = a.getHermesProfile() != null;
            try {
                if (agentService.ensureBot(a.getId())) {
                    if (had) updated++; else created++;
                }
            } catch (AppException e) {
                failed.add(new Failed(a.getId().toString(), e.getMessage()));
            }
        }
        return new SyncAll(created, updated, failed);
    }

    // ------------------------------------------------------------------ cấu hình bot (theo agent)
    /** Cấu hình thật của bot trên Hermes: model, provider, địa chỉ, ngữ cảnh, SOUL, plugin, tên biến .env (không có giá trị), gateway. */
    public JsonNode bot(UUID agentId) { return hermes.get(botOf(agentId)); }

    /** Sửa model/provider/baseUrl/contextLength của bot. Cần restart bot để có hiệu lực. Model ghi cả vào hồ sơ agent, để lần lưu hồ sơ sau không đổi ngược lại. */
    public JsonNode updateBot(UUID agentId, JsonNode body) {
        JsonNode result = hermes.update(botOf(agentId), body);
        if (body.hasNonNull("model")) agentService.recordBotModel(agentId, body.get("model").asText());
        return result;
    }

    // ------------------------------------------------------------------ bot theo tên (/api/hermes/bots)
    /** Bot của agent chỉ sửa được thông số kết nối ở đây; model, SOUL, mô tả phải sửa ở hồ sơ agent. */
    public JsonNode updateNamedBot(String name, JsonNode body) {
        Optional<Agent> owner = ownerOf(name);
        if (owner.isPresent() && OWNED_BY_AGENT.stream().anyMatch(body::has)) {
            throw conflict("Bot '" + name + "' thuộc agent '" + owner.get().getName() + "': model, SOUL, mô tả sửa ở hồ sơ agent");
        }
        return hermes.update(name, body);
    }

    /** Không xoá bot mẫu, bot của agent (xoá agent thì bot đi theo) và bot đang chạy gateway. */
    public JsonNode deleteNamedBot(String name) {
        if (name.equals(props.cloneFrom().trim())) throw conflict("Không xoá được bot mẫu '" + name + "' (HERMES_CLONE_FROM)");
        ownerOf(name).ifPresent(a -> { throw conflict("Bot '" + name + "' thuộc agent '" + a.getName() + "': hãy xoá agent"); });
        if ("running".equals(hermes.get(name).path("gateway").asText())) throw conflict("Bot '" + name + "' đang chạy gateway, hãy dừng trước khi xoá");
        return hermes.delete(name);
    }

    private Optional<Agent> ownerOf(String bot) {
        return agents.findByArchivedAtIsNull(org.springframework.data.domain.Sort.by("createdAt")).stream()
                .filter(a -> bot.equals(a.getHermesProfile())).findFirst();
    }

    private static AppException conflict(String message) { return new AppException(ErrorCode.CONFLICT, message); }

    /** Mất bot mẫu thì agent mới không có bot: báo ngay lúc khởi động thay vì đợi tới lần tạo agent. */
    @EventListener(ApplicationReadyEvent.class)
    void checkCloneTemplate() {
        String from = props.cloneFrom().trim();
        if (from.isEmpty() || !hermes.configured()) return;
        Thread.startVirtualThread(() -> {
            try {
                hermes.get(from);
            } catch (NotFoundException e) {
                log.error("Bot mẫu '{}' không có trên Hermes: agent mới sẽ không có bot, kiểm tra HERMES_CLONE_FROM", from);
            } catch (AppException e) {
                log.warn("Chưa kiểm tra được bot mẫu '{}': {}", from, e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ skill của bot (theo agent)
    public JsonNode skills(UUID agentId) { return hermes.skills(botOf(agentId)); }

    public JsonNode skill(UUID agentId, String skill) { return hermes.skill(botOf(agentId), skill); }

    /** body = {"enabled": {"ten-skill": true|false}}. */
    public JsonNode setSkillsEnabled(UUID agentId, JsonNode body) { return hermes.setSkillsEnabled(botOf(agentId), body); }

    /** body = {enabled?: bool, content?: "SKILL.md mới"}. */
    public JsonNode updateSkill(UUID agentId, String skill, JsonNode body) { return hermes.updateSkill(botOf(agentId), skill, body); }

    // ------------------------------------------------------------------ kênh YouTube (theo agent)
    public JsonNode channels(UUID agentId) { return hermes.channels(botOf(agentId)); }

    public JsonNode channelAction(UUID agentId, String action, String channel, String machine) {
        return hermes.channelAction(botOf(agentId), action, channel, machine);
    }

    // ------------------------------------------------------------------ lịch tự động (theo agent)
    public JsonNode schedule(UUID agentId) { return hermes.schedule(botOf(agentId)); }

    public JsonNode scheduleAction(UUID agentId, String action, JsonNode body) {
        return hermes.scheduleAction(botOf(agentId), action, body);
    }

    private String botOf(UUID agentId) {
        Agent a = agents.findById(agentId).filter(x -> x.getArchivedAt() == null).orElseThrow(() -> new NotFoundException("agent", agentId));
        if (a.getHermesProfile() == null)
            throw new AppException(com.agentoffice.exception.ErrorCode.CONFLICT, "Agent chưa có bot trên Hermes, hãy đồng bộ trước");
        return a.getHermesProfile();
    }

    private AgentSync one(UUID id) {
        Agent a = agents.findById(id).orElseThrow(() -> new NotFoundException("agent", id));
        try {
            return view(a, botNames(), null);
        } catch (AppException e) {
            return view(a, null, e.getMessage());
        }
    }

    private AgentSync view(Agent a, Set<String> bots, String error) {
        String id = a.getId().toString();
        String p = a.getHermesProfile();
        if (bots == null) return new AgentSync(id, a.getName(), p, "ERROR", error, null);
        if (p == null) return new AgentSync(id, a.getName(), null, "NOT_SYNCED", null, null);
        return new AgentSync(id, a.getName(), p, bots.contains(p) ? "SYNCED" : "MISSING", null, null);
    }

    /** hermes_admin trả mảng hoặc object bọc {profiles:[...]}; mỗi phần tử là chuỗi hoặc object có "name". */
    private Set<String> botNames() {
        JsonNode root = hermes.list();
        JsonNode arr = root.isArray() ? root : root.path("profiles");
        Set<String> names = new HashSet<>();
        for (JsonNode n : arr) {
            String name = n.isTextual() ? n.asText() : n.path("name").asText("");
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }
}
