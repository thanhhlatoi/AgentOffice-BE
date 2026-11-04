package com.agentoffice.service.system;

import com.agentoffice.entity.system.SystemState;
import com.agentoffice.repository.system.SystemStateRepository;
import com.agentoffice.service.audit.PermissionAuditLog;
import com.agentoffice.service.event.EventPublisher;
import com.agentoffice.domain.event.EventTypes;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pause hệ thống: không nhận task mới vào RUNNING; task đang chạy hoàn tất rồi dừng. */
@Service
public class SystemStateService {
    static final String PAUSED = "paused";
    private final SystemStateRepository repo;
    private final EventPublisher events;
    private final PermissionAuditLog audit;

    public SystemStateService(SystemStateRepository repo, EventPublisher events, PermissionAuditLog audit) {
        this.repo = repo;
        this.events = events;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public boolean isPaused() {
        return repo.findById(PAUSED).map(s -> Boolean.parseBoolean(s.getValue())).orElse(false);
    }

    /** Idempotent: pause lúc đang pause không phát lại event. */
    @Transactional
    public boolean pause(String actor) { return set(true, actor); }

    @Transactional
    public boolean resume(String actor) { return set(false, actor); }

    private boolean set(boolean paused, String actor) {
        SystemState s = repo.findById(PAUSED).orElseGet(() -> new SystemState(PAUSED, "false"));
        if (Boolean.parseBoolean(s.getValue()) == paused) return false;
        s.setValue(Boolean.toString(paused));
        repo.save(s);
        events.publish(paused ? EventTypes.SYSTEM_PAUSED : EventTypes.SYSTEM_RESUMED, null, Map.of("actor", actor));
        audit.record(actor, paused ? "SYSTEM_PAUSE" : "SYSTEM_RESUME", Map.of());
        return true;
    }
}
