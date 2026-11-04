package com.agentoffice.entity.usage;

import lombok.Getter;
import lombok.Setter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import com.agentoffice.domain.usage.BudgetPeriodType;
import com.agentoffice.domain.usage.BudgetScope;
import com.agentoffice.domain.usage.HardAction;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "budget")
public class Budget {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Enumerated(EnumType.STRING)
    private BudgetScope scopeType;
    private UUID scopeId;
    @Enumerated(EnumType.STRING)
    private BudgetPeriodType period;
    @Column(name = "limit_micro_usd")
    @Setter
    private long limitMicroUsd;
    @Setter
    private Long limitTokens;
    @Setter
    private int softPct = 80;
    @Enumerated(EnumType.STRING)
    @Setter
    private HardAction hardAction = HardAction.BLOCK;
    @Setter
    private boolean enabled = true;
    private Instant createdAt = Instant.now();

    public Budget(BudgetScope scopeType, UUID scopeId, BudgetPeriodType period, long limitMicroUsd, Long limitTokens,
                  int softPct, HardAction hardAction) {
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.period = period;
        this.limitMicroUsd = limitMicroUsd;
        this.limitTokens = limitTokens;
        this.softPct = softPct;
        this.hardAction = hardAction;
    }

}
