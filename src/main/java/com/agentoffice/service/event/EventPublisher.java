package com.agentoffice.service.event;

import com.agentoffice.entity.event.DomainEvent;
import com.agentoffice.repository.event.DomainEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi sự kiện vào bảng outbox TRONG CÙNG transaction với thay đổi nghiệp vụ.
 * Nhờ vậy không bao giờ có chuyện "đã đổi trạng thái nhưng mất sự kiện".
 * (Mandatory: gọi ngoài transaction sẽ lỗi, để bạn không quên.)
 */
@Component
public class EventPublisher {
    private final DomainEventRepository repo;
    private final ObjectMapper mapper;

    public EventPublisher(DomainEventRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public DomainEvent publish(String type, UUID aggregateId, Object payload) {
        if (payload instanceof Map<?, ?> m && !m.containsKey("occurredAt")) {
            Map<Object, Object> withTime = new LinkedHashMap<>(m);
            withTime.put("occurredAt", Instant.now().toString());   // mọi event đều có occurredAt
            payload = withTime;
        }
        try {
            return repo.save(new DomainEvent(type, aggregateId, mapper.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload not serializable", e);
        }
    }
}
