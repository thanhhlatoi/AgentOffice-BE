package com.agentoffice.service.debug;

import com.agentoffice.repository.debug.DiagnosticsRepository;
import com.agentoffice.service.event.EventStreamService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Profile("dev")
@Service
public class DiagnosticsService {
    private final DiagnosticsRepository repo;
    private final EventStreamService stream;

    public DiagnosticsService(DiagnosticsRepository repo, EventStreamService stream) {
        this.repo = repo;
        this.stream = stream;
    }

    public Map<String, Object> queue() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tasksByStatus", repo.tasksByStatus());
        out.put("goalsByStatus", repo.goalsByStatus());
        out.put("oldestQueuedSeconds", repo.oldestQueuedSeconds());
        out.put("pendingApprovals", repo.pendingApprovals());
        return out;
    }

    /** id event mới nhất so với con trỏ của subscriber SSE chậm nhất. lag lớn kéo dài = relay/FE đang tụt. */
    public Map<String, Object> outboxLag() {
        long latest = repo.latestEventId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("latestEventId", latest);
        out.put("subscribers", stream.subscriberCount());
        stream.slowestCursor().ifPresent(c -> { out.put("slowestSubscriberCursor", c); out.put("lag", latest - c); });
        return out;
    }
}
