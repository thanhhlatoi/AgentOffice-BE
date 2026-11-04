package com.agentoffice.entity.task;

import lombok.Getter;
import lombok.Setter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import com.agentoffice.domain.task.Priority;
import com.agentoffice.domain.task.TaskStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "task")
public class Task {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private UUID goalId;
    private UUID parentTaskId;
    private UUID assigneeAgentId;
    private String createdBy;          // HUMAN | ORCHESTRATOR | <agentId>
    private String title;
    private String input;

    @Enumerated(EnumType.STRING)
    private TaskStatus status = TaskStatus.PENDING;

    @Enumerated(EnumType.STRING)
    private Priority priority = Priority.NORMAL;

    private int depth;
    private int attempts;
    private int maxAttempts = 3;
    private Instant nextRunAt;
    private boolean cancelRequested;
    private String result;
    private String error;
    @Setter
    private Instant startedAt;
    @Setter
    private Instant finishedAt;
    /** Chỉ đọc với JPA (DB cấp qua trigger khi INSERT; đổi bằng câu UPDATE có kiểm version trong TaskRepository). */
    @Column(name = "kanban_rank", insertable = false, updatable = false)
    private String kanbanRank;
    /** Chỉ đọc với JPA sau khi tạo: setProjectId chỉ có tác dụng TRƯỚC INSERT; đổi về sau qua TaskRepository.moveGoalTasksToProject. */
    @Column(name = "project_id", updatable = false)
    @Setter
    private UUID projectId;
    private int weight = 1;
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private List<String> labels = new ArrayList<>();
    @Setter
    private UUID retryOfTaskId;
    private UUID waitingBudgetId;
    private Long waitingNeedMicro;
    private Instant statusSince;
    private long activeDurationSec;
    private long waitDurationSec;

    @Version
    private long version;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public Task(UUID goalId, UUID parentTaskId, UUID assigneeAgentId, String createdBy,
                String title, String input, Priority priority, int depth) {
        this.goalId = goalId;
        this.parentTaskId = parentTaskId;
        this.assigneeAgentId = assigneeAgentId;
        this.createdBy = createdBy;
        this.title = title;
        this.input = input;
        this.priority = priority == null ? Priority.NORMAL : priority;
        this.depth = depth;
    }

    public void setInput(String input) { this.input = input; touch(); }

    /**
     * CHỈ TaskService được gọi hàm này (ArchitectureTest canh): mọi đổi trạng thái phải đi qua TaskService.transition.
     */
    public void setStatus(TaskStatus s) { this.status = s; touch(); }

    public void setPriority(Priority p) { this.priority = p; touch(); }
    public void setAttempts(int a) { this.attempts = a; touch(); }
    public void setNextRunAt(Instant t) { this.nextRunAt = t; touch(); }
    public void setCancelRequested(boolean b) { this.cancelRequested = b; touch(); }
    public void setResult(String r) { this.result = r; touch(); }
    public void setError(String e) { this.error = e; touch(); }
    public void setWeight(int w) { this.weight = w; touch(); }
    public void setLabels(List<String> l) { this.labels = new ArrayList<>(l); touch(); }
    public void reassign(UUID agentId) { this.assigneeAgentId = agentId; touch(); }
    public void setWaitingBudget(UUID budgetId, Long needMicro) { this.waitingBudgetId = budgetId; this.waitingNeedMicro = needMicro; }
    /** Gọi bởi TaskService.transition khi rời một trạng thái: cộng thời gian đã ở đó và ghi mốc vào trạng thái mới. */
    public void leaveStatus(TaskStatus from, Instant now) {
        long secs = Math.max(0, java.time.Duration.between(statusSince != null ? statusSince : createdAt, now).getSeconds());
        if (from == TaskStatus.RUNNING) activeDurationSec += secs;
        else if (from == TaskStatus.WAITING_APPROVAL || from == TaskStatus.WAITING_BUDGET) waitDurationSec += secs;
        statusSince = now;
    }
    private void touch() { this.updatedAt = Instant.now(); }
}
