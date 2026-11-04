package com.agentoffice.service.usage;

import com.agentoffice.config.UsageProperties;
import com.agentoffice.domain.event.EventTypes;
import com.agentoffice.dto.usage.UsageEntry;
import com.agentoffice.repository.usage.UsageRepository;
import com.agentoffice.service.event.EventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi UsageRecord + cộng dồn UsageDaily trong CÙNG một transaction (chọn cách này thay vì job gộp: không bao giờ
 * lệch hay đếm trùng, vì cả hai cùng commit hoặc cùng huỷ). REQUIRES_NEW: tiền đã tiêu thì phải ghi dù transaction
 * bên ngoài (nếu có) rollback.
 */
@Service
public class UsageRecorder {
    private final UsageRepository repo;
    private final EventPublisher events;
    private final UsageProperties props;
    private final Clock clock;
    /** "provider/model" -> lần cảnh báo cuối. Trong bộ nhớ: restart thì có thể cảnh báo lại 1 lần (chấp nhận). */
    private final Map<String, Instant> lastUnpricedAlert = new ConcurrentHashMap<>();

    public UsageRecorder(UsageRepository repo, EventPublisher events, UsageProperties props, Clock clock) {
        this.repo = repo;
        this.events = events;
        this.props = props;
        this.clock = clock;
    }

    /** @return false nếu requestId đã có (không ghi gì thêm). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean record(UsageEntry e) {
        if (!repo.insertRecord(e)) return false;
        repo.addDaily(e.occurredAt().atZone(props.reportZone()).toLocalDate(), e);
        if (!e.priced() && shouldAlert(e.provider() + "/" + e.model())) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("provider", e.provider());
            p.put("model", e.model());
            events.publish(EventTypes.USAGE_UNPRICED, null, p);
        }
        return true;
    }

    private boolean shouldAlert(String key) {
        Instant now = clock.instant();
        boolean[] fire = {false};
        lastUnpricedAlert.compute(key, (k, last) -> {
            if (last == null || !now.isBefore(last.plus(props.unpricedAlertInterval()))) { fire[0] = true; return now; }
            return last;
        });
        return fire[0];
    }
}
