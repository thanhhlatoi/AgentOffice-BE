package com.agentoffice.repository.task;

import com.agentoffice.entity.task.Goal;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface GoalRepository extends JpaRepository<Goal, UUID> {
    /** Khoá hàng goal để tuần tự hoá: đếm task, tổng hợp kết quả khi nhiều task kết thúc cùng lúc. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Goal g where g.id = :id")
    Optional<Goal> findByIdForUpdate(UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Goal g set g.tokensUsed = g.tokensUsed + :n where g.id = :id")
    int addTokens(UUID id, long n);
}
