package com.agentoffice.entity.task;

import lombok.Getter;
import lombok.Setter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import com.agentoffice.domain.task.GoalStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "goal")
public class Goal {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String title;
    private String description;

    /** setStatus CHỈ GoalService được gọi (ArchitectureTest canh). */
    @Enumerated(EnumType.STRING)
    @Setter
    private GoalStatus status = GoalStatus.DRAFT;

    @Setter
    private UUID projectId;
    private UUID departmentId;
    private Long tokenBudget;            // null = không giới hạn
    private long tokensUsed;
    private Instant createdAt = Instant.now();
    @Setter
    private Instant finishedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @Setter
    private String plan;
    private int planAttempts;
    private int planRejections;
    @Setter
    private String result;
    @Setter
    private String error;

    public Goal(String title, String description, UUID departmentId, Long tokenBudget) {
        this.title = title;
        this.description = description;
        this.departmentId = departmentId;
        this.tokenBudget = tokenBudget;
    }

    public void addPlanAttempt() { this.planAttempts++; }
    public void addPlanRejection() { this.planRejections++; }
}
