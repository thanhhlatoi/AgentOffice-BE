package com.agentoffice.repository.usage;

import com.agentoffice.domain.usage.BudgetScope;
import com.agentoffice.entity.usage.Budget;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Budget b where b.id = :id")
    Optional<Budget> findByIdForUpdate(UUID id);

    List<Budget> findByScopeType(BudgetScope scopeType);

    /**
     * Chuỗi ngân sách BẬT áp dụng cho một cuộc gọi: GLOBAL + DEPARTMENT/AGENT/PROJECT/GOAL khớp id (id null thì bỏ qua loại đó).
     * Sắp theo id: mọi luồng khoá các dòng counter theo CÙNG một thứ tự để không deadlock.
     */
    @Query("""
            select b from Budget b where b.enabled = true and (
                b.scopeType = com.agentoffice.domain.usage.BudgetScope.GLOBAL
                or (b.scopeType = com.agentoffice.domain.usage.BudgetScope.DEPARTMENT and b.scopeId = :departmentId)
                or (b.scopeType = com.agentoffice.domain.usage.BudgetScope.AGENT and b.scopeId = :agentId)
                or (b.scopeType = com.agentoffice.domain.usage.BudgetScope.PROJECT and b.scopeId = :projectId)
                or (b.scopeType = com.agentoffice.domain.usage.BudgetScope.GOAL and b.scopeId = :goalId))
            order by b.id""")
    List<Budget> findApplicable(UUID departmentId, UUID agentId, UUID projectId, UUID goalId);
}
