package com.agentoffice.service.agent;

import com.agentoffice.client.hermes.HermesBotClient;
import com.agentoffice.config.HermesAdminProperties;
import com.agentoffice.entity.agent.Agent;
import com.agentoffice.repository.agent.AgentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tạo agent kèm bot tương ứng trên Hermes. Hermes admin chưa cấu hình thì chỉ tạo agent (hermesProfile = null).
 * Đã cấu hình mà Hermes lỗi thì ném lỗi và rollback, để DB và Hermes không lệch nhau (không có agent mà thiếu bot).
 */
@Service
public class AgentService {
    private final AgentRepository repo;
    private final HermesBotClient hermes;
    private final HermesAdminProperties props;
    private final ObjectMapper mapper;

    public AgentService(AgentRepository repo, HermesBotClient hermes, HermesAdminProperties props, ObjectMapper mapper) {
        this.repo = repo;
        this.hermes = hermes;
        this.props = props;
        this.mapper = mapper;
    }

    @Transactional
    public Agent create(String name, String role, String department) {
        Agent agent = repo.save(new Agent(name, role, department));
        if (hermes.configured()) {
            String profile = profileName(name, agent);
            hermes.create(botBody(profile, name, role, department));
            agent.setHermesProfile(profile);
        }
        return agent;
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

    private ObjectNode botBody(String profile, String name, String role, String department) {
        ObjectNode body = mapper.createObjectNode();
        body.put("name", profile);
        if (!props.cloneFrom().isBlank()) body.put("cloneFrom", props.cloneFrom().trim());
        body.put("description", role + " - " + department);
        body.put("soul", "Bạn là " + name + ", vai trò " + role + ", thuộc phòng " + department + ".");
        return body;
    }
}
