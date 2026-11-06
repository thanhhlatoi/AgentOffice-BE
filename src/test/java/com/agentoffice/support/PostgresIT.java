package com.agentoffice.support;

import com.agentoffice.entity.agent.Agent;
import com.agentoffice.repository.agent.AgentRepository;
import com.agentoffice.entity.event.DomainEvent;
import com.agentoffice.repository.event.DomainEventRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Base cho test tích hợp: 1 Postgres (pgvector) dùng chung cả JVM, DB được dọn sạch trước mỗi test. */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "agentoffice.llm.provider=fake")   // .env của dev (LLM_PROVIDER) không được làm test gọi gateway thật
@Import(TestClockConfig.class)
public abstract class PostgresIT {
    public static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withCommand("postgres", "-c", "max_connections=400");   // mỗi tổ hợp @Import là một Spring context riêng, mỗi context giữ một pool

    static { PG.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected AgentRepository agentRepo;
    @Autowired protected DomainEventRepository eventRepo;
    @Autowired protected MutableClock clock;

    /** Xoá dữ liệu nghiệp vụ (giữ approval_policy đã seed). session_replication_role=replica tắt trigger append-only để TRUNCATE. */
    @BeforeEach
    void cleanDatabase() {
        clock.reset();
        jdbc.execute((ConnectionCallback<Void>) c -> {
            try (var s = c.createStatement()) {
                s.execute("SET session_replication_role = replica");
                s.execute("TRUNCATE budget_change, budget_counter, usage_record, usage_daily, approval_audit, approval, task_dependency, task, goal, agent, domain_event, permission_audit RESTART IDENTITY CASCADE");
                // ngân sách: chỉ giữ 2 dòng GLOBAL mẫu của V10 (tạo lại để test nào sửa/tắt chúng không ảnh hưởng test khác)
                s.execute("DELETE FROM budget");
                s.execute("INSERT INTO budget (scope_type, scope_id, period, limit_micro_usd) VALUES ('GLOBAL', NULL, 'DAY', 5000000), ('GLOBAL', NULL, 'MONTH', 100000000)");
                s.execute("SET session_replication_role = DEFAULT");
                s.execute("UPDATE system_state SET value = 'false' WHERE key = 'paused'");
            }
            return null;
        });
    }

    protected Agent agent(String name) { return agent(name, 1); }

    protected Agent agent(String name, int maxConcurrent) {
        Agent a = new Agent(name, "tester", "Test");
        a.setMaxConcurrentTasks(maxConcurrent);
        return agentRepo.saveAndFlush(a);
    }

    protected List<DomainEvent> events() { return eventRepo.findAll(org.springframework.data.domain.Sort.by("id")); }

    protected List<String> eventTypes() { return events().stream().map(DomainEvent::getType).toList(); }

    protected UUID uuid() { return UUID.randomUUID(); }
}
