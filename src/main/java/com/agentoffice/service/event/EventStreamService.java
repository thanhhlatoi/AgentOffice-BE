package com.agentoffice.service.event;

import com.agentoffice.entity.event.DomainEvent;
import com.agentoffice.repository.event.DomainEventRepository;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Relay: đọc outbox theo id tăng dần rồi đẩy cho mọi client SSE đang nối. */
@Service
public class EventStreamService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EventStreamService.class);
    private final DomainEventRepository repo;
    private final List<Client> clients = new CopyOnWriteArrayList<>();

    private record Client(SseEmitter emitter, AtomicLong cursor) {}

    public EventStreamService(DomainEventRepository repo) {
        this.repo = repo;
    }

    public SseEmitter subscribe(long lastEventId) {
        SseEmitter emitter = new SseEmitter(0L);
        Client c = new Client(emitter, new AtomicLong(lastEventId));
        clients.add(c);
        emitter.onCompletion(() -> clients.remove(c));
        emitter.onTimeout(() -> clients.remove(c));
        emitter.onError(e -> clients.remove(c));
        return emitter;
    }

    /** Phục vụ chẩn đoán: con trỏ của subscriber chậm nhất (rỗng nếu không có ai đang nối). */
    public java.util.OptionalLong slowestCursor() {
        return clients.stream().mapToLong(c -> c.cursor().get()).min();
    }

    public int subscriberCount() { return clients.size(); }

    @Scheduled(fixedDelayString = "${agentoffice.events.relay-interval-ms}")
    void relay() {
        for (Client c : clients) {
            List<DomainEvent> batch = repo.findTop200ByIdGreaterThanOrderByIdAsc(c.cursor().get());
            for (DomainEvent e : batch) {
                try {
                    c.emitter().send(SseEmitter.event()
                            .id(String.valueOf(e.getId()))
                            .name(e.getType())
                            .data(e.getPayload()));
                    c.cursor().set(e.getId());
                } catch (IOException ex) {
                    log.debug("SSE client rớt tại event {} ({}), gỡ khỏi danh sách", c.cursor().get(), ex.getClass().getSimpleName());
                    clients.remove(c);
                    break;
                }
            }
        }
    }
}
