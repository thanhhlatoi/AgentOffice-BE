package com.agentoffice.entity.approval;

import lombok.Getter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only (trigger DB chặn UPDATE/DELETE/TRUNCATE; @Immutable chặn thêm ở JPA). */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Immutable
@Table(name = "approval_audit")
public class ApprovalAudit {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID approvalId;
    private String actor;
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String detail;
    private Instant createdAt = Instant.now();

    public ApprovalAudit(UUID approvalId, String actor, String action, String detailJson) {
        this.approvalId = approvalId;
        this.actor = actor;
        this.action = action;
        this.detail = detailJson;
    }

}
