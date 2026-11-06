package com.agentoffice.repository.approval;

import com.agentoffice.domain.approval.ApprovalKind;
import com.agentoffice.domain.approval.ApprovalStatus;
import com.agentoffice.entity.approval.Approval;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface ApprovalRepository extends JpaRepository<Approval, UUID> {
    Optional<Approval> findByIdempotencyKey(String idempotencyKey);
    List<Approval> findByStatusOrderByCreatedAtAsc(ApprovalStatus status);
    List<Approval> findTop50ByStatusNotOrderByCreatedAtDesc(ApprovalStatus status);
    List<Approval> findByStatusAndKind(ApprovalStatus status, ApprovalKind kind);
    List<Approval> findByStatusAndKindAndExpiresAtBefore(ApprovalStatus status, ApprovalKind kind, Instant t);
    Optional<Approval> findFirstByGoalIdAndKindAndStatus(UUID goalId, ApprovalKind kind, ApprovalStatus status);

    /** Chiếm approval NGUYÊN TỬ: chỉ một lời gọi thắng (PENDING -> status mới), kể cả hai tab bấm cùng lúc. 0 = đã có người xử lý hoặc quá hạn. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Approval a set a.status = :to, a.decidedBy = :by, a.decidedAt = :now, a.reason = :reason "
            + "where a.id = :id and a.status = com.agentoffice.domain.approval.ApprovalStatus.PENDING and a.expiresAt > :now")
    int claim(UUID id, ApprovalStatus to, String by, Instant now, String reason);

    /** Như claim nhưng KHÔNG đòi chưa hết hạn: dùng cho từ chối (từ chối một lời xin đã quá hạn vẫn là từ chối hợp lệ). */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Approval a set a.status = :to, a.decidedBy = :by, a.decidedAt = :now, a.reason = :reason "
            + "where a.id = :id and a.status = com.agentoffice.domain.approval.ApprovalStatus.PENDING")
    int close(UUID id, ApprovalStatus to, String by, Instant now, String reason);

    /** Khoá tư vấn theo khoá idempotency: hai request cùng (task, tool, args) xếp hàng, kẻ sau thấy approval của kẻ trước. */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    void lockKey(String key);

    /** Lời gọi tool quá hạn, khoá hàng; SKIP LOCKED để hai instance chạy job cùng lúc không xử lý trùng. */
    @Query(value = "SELECT * FROM approval WHERE status = 'PENDING' AND kind = 'TOOL_CALL' AND expires_at < :now ORDER BY created_at FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<Approval> lockOverdueToolCalls(Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Approval a set a.executedAt = :at where a.id = :id")
    int markExecuted(UUID id, Instant at);
}
