package com.agentoffice.entity.project;

import lombok.Getter;
import lombok.Setter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import com.agentoffice.domain.project.ProjectStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "project")
public class Project {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Setter
    private String name;
    @Setter
    private String description;
    @Enumerated(EnumType.STRING)
    @Setter
    private ProjectStatus status = ProjectStatus.ACTIVE;
    @Setter
    private UUID departmentId;
    @Setter
    private UUID ownerAgentId;
    @Setter
    private String color = "#4F46E5";
    @Setter
    private Instant deadline;
    @Setter
    private int progressPct;
    @Setter
    private boolean hasPlan;
    private Instant createdAt = Instant.now();
    @Setter
    private Instant finishedAt;

    public Project(String name, String description, UUID departmentId, UUID ownerAgentId, String color, Instant deadline) {
        this.name = name;
        this.description = description;
        this.departmentId = departmentId;
        this.ownerAgentId = ownerAgentId;
        if (color != null) this.color = color;
        this.deadline = deadline;
    }

}
