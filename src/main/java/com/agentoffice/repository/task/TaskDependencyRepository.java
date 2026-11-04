package com.agentoffice.repository.task;

import com.agentoffice.entity.task.TaskDependency;
import com.agentoffice.entity.task.TaskDependencyId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.UUID;
@Repository
public interface TaskDependencyRepository extends JpaRepository<TaskDependency, TaskDependencyId> {
    /** Các phụ thuộc của task (nó đang chờ ai). */
    @Query("select d from TaskDependency d where d.id.taskId = :taskId")
    List<TaskDependency> findByTaskId(UUID taskId);

    /** Các task đang chờ task này (dependents). */
    @Query("select d from TaskDependency d where d.id.dependsOnTaskId = :dependsOnTaskId")
    List<TaskDependency> findByDependsOnTaskId(UUID dependsOnTaskId);
}
