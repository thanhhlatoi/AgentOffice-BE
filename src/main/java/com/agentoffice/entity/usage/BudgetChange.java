package com.agentoffice.entity.usage;

import lombok.Getter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Chỉ-thêm (trigger DB canh). */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "budget_change")
public class BudgetChange {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID budgetId;
    @Column(name = "old_limit_micro_usd")
    private long oldLimit;
    @Column(name = "new_limit_micro_usd")
    private long newLimit;
    private String reason;
    private String changedBy;
    private Instant changedAt;

    public BudgetChange(UUID budgetId, long oldLimit, long newLimit, String reason, String changedBy, Instant changedAt) {
        this.budgetId = budgetId;
        this.oldLimit = oldLimit;
        this.newLimit = newLimit;
        this.reason = reason;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
    }

}
