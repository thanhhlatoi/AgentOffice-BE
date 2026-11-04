package com.agentoffice.controller.event;

import com.agentoffice.entity.event.DomainEvent;
import com.agentoffice.repository.event.DomainEventRepository;
import com.agentoffice.service.event.EventStreamService;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/events")
public class EventController {
    private final EventStreamService stream;
    private final DomainEventRepository repo;

    public EventController(EventStreamService stream, DomainEventRepository repo) {
        this.stream = stream;
        this.repo = repo;
    }

    /** SSE. Trình duyệt tự gửi header Last-Event-ID khi nối lại. */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId,
                             @RequestParam(required = false) Long afterId) {
        long from = lastEventId != null ? lastEventId : afterId != null ? afterId : 0L;
        return stream.subscribe(from);
    }

    /** Catch-up không cần SSE: FE gọi sau khi mất kết nối lâu. */
    @GetMapping
    public List<DomainEvent> after(@RequestParam(defaultValue = "0") long afterId) {
        return repo.findTop200ByIdGreaterThanOrderByIdAsc(afterId);
    }
}
