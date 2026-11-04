package com.agentoffice.service.usage;

import com.agentoffice.dto.usage.UsageDto;
import com.agentoffice.exception.AppException;
import com.agentoffice.exception.ErrorCode;
import com.agentoffice.repository.usage.UsageRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UsageService {
    static final Set<String> GROUPS = Set.of("day", "agent", "project", "department", "model");
    static final int MAX_DAYS = 366;

    private final UsageRepository repo;

    public UsageService(UsageRepository repo) { this.repo = repo; }

    /**
     * Luôn đọc usage_daily (kể cả khoảng ngắn): bảng này được cộng dồn cùng transaction với usage_record nên khớp tuyệt đối,
     * quét usage_record cho khoảng ≤ 48h chỉ thêm code mà không đổi kết quả. `from`/`to` là NGÀY theo múi giờ báo cáo, gồm cả hai đầu.
     */
    @Transactional(readOnly = true)
    public List<UsageDto.SummaryRow> summary(LocalDate from, LocalDate to, String groupBy, UUID agentId, UUID projectId) {
        if (!GROUPS.contains(groupBy)) throw new AppException(ErrorCode.VALIDATION, "groupBy phải là một trong " + GROUPS);
        if (to.isBefore(from)) throw new AppException(ErrorCode.VALIDATION, "to phải >= from");
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) throw new AppException(ErrorCode.VALIDATION, "Khoảng tối đa " + MAX_DAYS + " ngày");
        return repo.summary(from, to, groupBy, agentId, projectId);
    }

    @Transactional(readOnly = true)
    public UsageDto.RecordPage records(String cursor, UUID agentId, UUID projectId, UUID taskId, int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        Instant at = null;
        UUID id = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                String[] c = cursor.split("_", 2);
                at = Instant.ofEpochSecond(0, Long.parseLong(c[0]) * 1000);   // micro giây, khớp độ chính xác của Postgres
                id = UUID.fromString(c[1]);
            } catch (RuntimeException e) {
                throw new AppException(ErrorCode.VALIDATION, "cursor không hợp lệ");
            }
        }
        List<UsageDto.RecordRow> rows = repo.records(at, id, agentId, projectId, taskId, n);
        if (rows.size() <= n) return new UsageDto.RecordPage(rows, null);
        List<UsageDto.RecordRow> page = rows.subList(0, n);
        UsageDto.RecordRow last = page.get(n - 1);
        long micros = last.occurredAt().getEpochSecond() * 1_000_000L + last.occurredAt().getNano() / 1000;
        return new UsageDto.RecordPage(page, micros + "_" + last.id());
    }

    @Transactional(readOnly = true)
    public List<UsageDto.UnpricedRow> unpriced() { return repo.unpriced(); }
}
