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
    @Setter
    private String name;
    @Setter
    private String role;
    @Setter
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
    /** SOUL của bot trên Hermes; null = tự dựng câu giới thiệu. */
    @Setter
    private String systemPrompt;
    @Setter
    private String color;
    /** Nhãn chuyên môn tự do: Planner dùng để chọn agent giao việc. Không phải skill của bot Hermes (xem /hermes/skills). */
    @Setter
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private java.util.List<String> skills = new java.util.ArrayList<>();
    private Instant createdAt = Instant.now();
    /** Có giá trị = đã "xoá" (lưu trữ): ẩn khỏi FE, OFFLINE, không còn bot Hermes. Dòng được giữ để lịch sử vẫn tham chiếu được. */
    @Setter
    private Instant archivedAt;

    public Agent(String name, String role, String department) {
        this.name = name;
        this.role = role;
        this.department = department;
        registerEvent(new AgentCreated(this));   // Spring Data phát khi save(); listener lo department + ngân sách mặc định
    }

    /** OFFLINE = đã tắt: không nhận task mới. */
    public boolean isActive() { return !"OFFLINE".equals(status); }
}
