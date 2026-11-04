package com.agentoffice.entity.task;

import lombok.Getter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** "taskId chỉ chạy được khi dependsOnTaskId đã SUCCEEDED". */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "task_dependency")
public class TaskDependency {
    @EmbeddedId
    private TaskDependencyId id;

    public TaskDependency(UUID taskId, UUID dependsOnTaskId) { this.id = new TaskDependencyId(taskId, dependsOnTaskId); }

    public UUID getTaskId() { return id.taskId(); }
    public UUID getDependsOnTaskId() { return id.dependsOnTaskId(); }
}
