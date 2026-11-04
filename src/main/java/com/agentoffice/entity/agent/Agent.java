package com.agentoffice.entity.agent;

import lombok.Getter;
import lombok.Setter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.AbstractAggregateRoot;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "agent")
public class Agent extends AbstractAggregateRoot<Agent> {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String name;
    private String role;
    private String department;
    /** Chỉ đọc với JPA: AgentCreatedListener gán qua SQL, để một Agent cũ bị lưu lại không ghi đè về null. */
    @Column(name = "department_id", insertable = false, updatable = false)
    private UUID departmentId;
    @Setter
    private String status = "IDLE";
    @Setter
    private String modelProfile;
    @Setter
    private int maxConcurrentTasks = 1;
    /** Tên bot (profile) của agent này trên Hermes; null = chưa có. */
    @Setter
    private String hermesProfile;
    private Instant createdAt = Instant.now();

    public Agent(String name, String role, String department) {
        this.name = name;
        this.role = role;
        this.department = department;
        registerEvent(new AgentCreated(this));   // Spring Data phát khi save(); listener lo department + ngân sách mặc định
    }

    /** OFFLINE = đã tắt: không nhận task mới. */
    public boolean isActive() { return !"OFFLINE".equals(status); }
}
