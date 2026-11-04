package com.agentoffice.repository.task;

import com.agentoffice.domain.task.TaskStatus;
import com.agentoffice.entity.task.Task;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskRepository extends JpaRepository<Task, UUID>, JpaSpecificationExecutor<Task> {
    List<Task> findByGoalIdOrderByCreatedAtAsc(UUID goalId);
    List<Task> findByParentTaskId(UUID parentTaskId);
    List<Task> findByStatus(TaskStatus status);
    long countByGoalId(UUID goalId);
    long countByStatus(TaskStatus status);
    long countByAssigneeAgentIdAndStatus(UUID assigneeAgentId, TaskStatus status);

    /** Khoá hàng task để tuần tự hoá việc mở khoá dependents (chống lost wake-up). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Task t where t.id = :id")
    Optional<Task> findByIdForUpdate(UUID id);

    /** Số phụ thuộc của task chưa SUCCEEDED. JPQL nên luôn đọc DB mới, không đọc cache cấp 1 của entity. */
    @Query("select count(t) from TaskDependency d, Task t where d.id.taskId = :taskId and t.id = d.id.dependsOnTaskId "
            + "and t.status <> com.agentoffice.domain.task.TaskStatus.SUCCEEDED")
    long countUnfinishedDependencies(UUID taskId);

    /**
     * Task QUEUED chạy được ngay, đã loại agent OFFLINE và cắt theo chỗ trống của từng agent
     * (max_concurrent_tasks - số RUNNING hiện tại). Cắt trong SQL để task của agent đang bận không chiếm chỗ LIMIT.
     * FOR UPDATE SKIP LOCKED: hàng đang bị giao dịch khác giữ (vd huỷ) thì bỏ qua, không chờ.
     */
    @Query(value = """
            SELECT t.* FROM task t WHERE t.id IN (
                SELECT x.id FROM (
                    SELECT q.id, q.created_at, q.kanban_rank,
                           CASE q.priority WHEN 'HIGH' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END AS prio,
                           row_number() OVER (PARTITION BY q.assignee_agent_id ORDER BY
                               CASE q.priority WHEN 'HIGH' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END, q.kanban_rank, q.created_at) AS rn,
                           a.max_concurrent_tasks - COALESCE(r.c, 0) AS free
                    FROM task q
                    JOIN agent a ON a.id = q.assignee_agent_id
                    LEFT JOIN (SELECT assignee_agent_id, count(*) AS c FROM task WHERE status = 'RUNNING'
                               GROUP BY assignee_agent_id) r ON r.assignee_agent_id = q.assignee_agent_id
                    WHERE q.status = 'QUEUED' AND (q.next_run_at IS NULL OR q.next_run_at <= :now)
                      AND a.status <> 'OFFLINE'
                ) x WHERE x.rn <= x.free
                ORDER BY x.prio, x.kanban_rank, x.created_at LIMIT :lim)
            ORDER BY CASE t.priority WHEN 'HIGH' THEN 0 WHEN 'NORMAL' THEN 1 ELSE 2 END, t.kanban_rank, t.created_at
            FOR UPDATE OF t SKIP LOCKED
            """, nativeQuery = true)
    List<Task> lockRunnable(Instant now, int lim);

    /** Đổi hạng bằng một câu UPDATE có điều kiện version (tăng version): 0 dòng = thẻ vừa bị người khác đổi. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE task SET kanban_rank = :rank, version = version + 1, updated_at = now() WHERE id = :id AND version = :version",
            nativeQuery = true)
    int updateRank(UUID id, String rank, long version);

    /** Chuyển mọi task của goal sang dự án khác (hoặc null). Tăng version để thẻ đang mở ở FE thành cũ. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Task t set t.projectId = :projectId, t.version = t.version + 1 where t.goalId = :goalId")
    int moveGoalTasksToProject(UUID goalId, UUID projectId);

    /** Hạng nhỏ nhất/lớn nhất đang có (so sánh theo byte nhờ COLLATE "C" của cột). */
    @Query(value = "SELECT kanban_rank FROM task WHERE id = :id", nativeQuery = true)
    String rankOf(UUID id);

    /** Khoá tư vấn theo transaction: chỉ 1 worker tính chỗ trống của agent tại một thời điểm. */
    @Query(value = "SELECT pg_advisory_xact_lock(:key)", nativeQuery = true)
    void advisoryLock(long key);
}
