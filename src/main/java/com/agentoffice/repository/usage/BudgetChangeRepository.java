package com.agentoffice.repository.usage;

import com.agentoffice.entity.usage.BudgetChange;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetChangeRepository extends JpaRepository<BudgetChange, UUID> {
    List<BudgetChange> findByBudgetIdOrderByChangedAtDesc(UUID budgetId);
    boolean existsByBudgetId(UUID budgetId);
}
