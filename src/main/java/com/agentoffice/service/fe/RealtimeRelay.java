package com.agentoffice.service.fe;

import com.agentoffice.domain.event.EventTypes;
import com.agentoffice.dto.fe.OfficeDto;
import com.agentoffice.entity.event.DomainEvent;
import com.agentoffice.repository.event.DomainEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Đọc outbox (domain_event) rồi đẩy cho FE qua STOMP /topic/agents, đúng dạng message FE (types/index.ts RealtimeMessageSchema).
 * Không gắn "id" vào message: id outbox bị hở khi transaction rollback, mà FE coi hở là mất sự kiện và đi bù. Thiếu id thì FE áp ngay.
 * Mỗi message mang trạng thái HIỆN TẠI đọc từ DB (không phải ảnh chụp lúc phát sinh), nên áp lại hay đến trễ đều vô hại.
 * Chỉ phát từ lúc BE khởi động; FE tự tải ảnh chụp ban đầu qua REST.
 */
@Component
public class RealtimeRelay {
    private static final Logger log = LoggerFactory.getLogger(RealtimeRelay.class);
    static final String TOPIC = "/topic/agents";

    private final DomainEventRepository events;
    private final ViewService view;
    private final SimpMessagingTemplate broker;
    private final ObjectMapper mapper;
    private Long cursor;   // chỉ luồng @Scheduled đụng tới

    public RealtimeRelay(DomainEventRepository events, ViewService view, SimpMessagingTemplate broker, ObjectMapper mapper) {
        this.events = events;
        this.view = view;
        this.broker = broker;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelayString = "${agentoffice.events.relay-interval-ms}")
    void relay() {
        if (cursor == null) { cursor = events.maxId(); return; }
        List<DomainEvent> batch = events.findTop200ByIdGreaterThanOrderByIdAsc(cursor);
        for (DomainEvent e : batch) {
            try {
                for (Map<String, Object> m : messagesFor(e)) send(m);
            } catch (Exception ex) {
                log.warn("Không đẩy được event {} ({}) cho FE: {}", e.getId(), e.getType(), ex.toString());
            }
            cursor = e.getId();
        }
    }

    List<Map<String, Object>> messagesFor(DomainEvent e) {
        UUID id = e.getAggregateId();
        String type = e.getType();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        switch (type) {
            case EventTypes.GOAL_CREATED -> out.add(msg("goal.created", "goal", view.goal(id)));
            case EventTypes.GOAL_PLAN_PROPOSED, EventTypes.GOAL_SUCCEEDED, EventTypes.GOAL_FAILED, EventTypes.GOAL_CANCELLED ->
                    out.add(msg("goal.updated", "goal", view.goal(id)));
            case EventTypes.TASK_CREATED, EventTypes.TASK_STATE_CHANGED, EventTypes.TASK_MOVED -> {
                OfficeDto.Task t = view.task(id);
                out.add(msg(EventTypes.TASK_CREATED.equals(type) ? "task.created" : "task.updated", "task", t));
                if (t.goalId() != null) out.add(msg("goal.updated", "goal", view.goal(UUID.fromString(t.goalId()))));
                if (!ViewService.NO_AGENT.equals(t.agentId())) {
                    OfficeDto.Agent a = view.agent(UUID.fromString(t.agentId()));
                    Map<String, Object> st = new LinkedHashMap<>();
                    st.put("type", "agent.status_changed");
                    st.put("agentId", a.id());
                    st.put("status", a.status());
                    st.put("currentTask", a.currentTask());
                    out.add(st);
                    if (EventTypes.TASK_STATE_CHANGED.equals(type)) {
                        OfficeDto.Agent ag = a;
                        Map<String, Object> ev = new LinkedHashMap<>();
                        ev.put("id", "evt-" + e.getId());
                        ev.put("agentId", ag.id());
                        ev.put("message", "Việc \"" + t.title() + "\" → " + t.status());
                        ev.put("createdAt", Instant.now().toString());
                        out.add(msg("event.created", "event", ev));
                    }
                }
            }
            case EventTypes.APPROVAL_REQUESTED -> {
                OfficeDto.Approval a = view.approval(id);
                Map<String, Object> m = msg("approval.requested", "approval", a);
                m.put("agentId", a.agentId());
                m.put("approvalId", a.id());
                out.add(m);
            }
            case EventTypes.APPROVAL_RESOLVED -> {
                OfficeDto.Approval a = view.approval(id);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "approval.resolved");
                m.put("approvalId", a.id());
                m.put("approved", "APPROVED".equals(a.status()));
                m.put("status", a.status());
                if (a.decisionReason() != null) m.put("reason", a.decisionReason());
                out.add(m);
                if (a.goalId() != null) out.add(msg("goal.updated", "goal", view.goal(UUID.fromString(a.goalId()))));
            }
            case EventTypes.SYSTEM_PAUSED, EventTypes.SYSTEM_RESUMED -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "system.state_changed");
                m.put("state", EventTypes.SYSTEM_PAUSED.equals(type) ? "PAUSED" : "RUNNING");
                out.add(m);
            }
            case EventTypes.DEPARTMENT_DISPATCH -> {
                // FE vẽ đường nối từ bàn trưởng phòng tới từng nhân viên (realtime/dispatch.ts)
                com.fasterxml.jackson.databind.JsonNode p;
                try {
                    p = mapper.readTree(e.getPayload());
                } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                    throw new IllegalStateException("payload department.dispatch hỏng", ex);
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "department.dispatch");
                m.put("departmentId", p.path("departmentId").asText());
                m.put("headId", p.path("headId").asText());
                List<String> targets = new java.util.ArrayList<>();
                p.path("targetAgentIds").forEach(t -> targets.add(t.asText()));
                m.put("targetAgentIds", targets);
                out.add(m);
            }
            case EventTypes.TEMPLATE_INSTANTIATED -> {
                // FE tải lại danh sách mẫu / agent (realtime/dispatch.ts)
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "template.instantiated");
                m.put("templateId", id.toString());
                try {
                    m.put("agentId", mapper.readTree(e.getPayload()).path("agentId").asText());
                } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                    throw new IllegalStateException("payload template.instantiated hỏng", ex);
                }
                out.add(m);
            }
            case EventTypes.MODEL_HEALTH_CHANGED -> {
                // FE cập nhật chấm sức khoẻ trong danh sách model, tải lại chính sách model (realtime/dispatch.ts)
                try {
                    com.fasterxml.jackson.databind.JsonNode p = mapper.readTree(e.getPayload());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("type", "model.health_changed");
                    m.put("modelId", p.path("modelId").asText());
                    m.put("health", mapper.convertValue(p.path("health"), Map.class));
                    out.add(m);
                } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                    throw new IllegalStateException("payload model.health_changed hỏng", ex);
                }
            }
            default -> { /* project.*, budget.*, usage.*: FE chưa nối phần này với BE, vẫn dùng mock */ }
        }
        return out;
    }

    private static Map<String, Object> msg(String type, String key, Object body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put(key, body);
        return m;
    }

    private void send(Map<String, Object> m) throws Exception {
        broker.convertAndSend(TOPIC, mapper.writeValueAsString(m));
    }
}
