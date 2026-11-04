package com.agentoffice.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** V11 trên DB ĐÃ CÓ task: hạng được cấp theo thứ tự tạo, project_id chép từ goal, so sánh hạng theo byte, task mới nhận hạng ở cuối. */
class MigrationV11Test {
    private static final String A = "00000000-0000-0000-0000-000000000001";
    private static final String P = "00000000-0000-0000-0000-0000000000c1";

    @Test
    void migratesExistingTasksSafely() throws Exception {
        try (var pg = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))) {
            pg.start();
            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).target("10").load().migrate();
            try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
                s.execute("INSERT INTO agent(id,name,role,department) VALUES ('" + A + "','A','r','d')");
                s.execute("INSERT INTO project(id,name) VALUES ('" + P + "','p')");
                s.execute("INSERT INTO goal(id,title,status,project_id) VALUES ('00000000-0000-0000-0000-0000000000a1','g','RUNNING','" + P + "')");
                s.execute("INSERT INTO task(id,goal_id,assignee_agent_id,title,created_at) VALUES "
                        + "('00000000-0000-0000-0000-0000000000b3','00000000-0000-0000-0000-0000000000a1','" + A + "','third','2026-01-03'),"
                        + "('00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-0000000000a1','" + A + "','first','2026-01-01'),"
                        + "('00000000-0000-0000-0000-0000000000b2',NULL,'" + A + "','second','2026-01-02')");

                Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();

                assertThat(one(s, "SELECT string_agg(title, ',' ORDER BY kanban_rank) FROM task")).as("hạng theo thứ tự tạo").isEqualTo("first,second,third");
                assertThat(one(s, "SELECT min(length(kanban_rank)) || ':' || max(length(kanban_rank)) FROM task")).isEqualTo("20:20");
                assertThat(one(s, "SELECT project_id FROM task WHERE title = 'first'")).isEqualTo(P);
                assertThat(one(s, "SELECT project_id IS NULL FROM task WHERE title = 'second'")).isEqualTo("t");
                assertThat(one(s, "SELECT weight || ':' || labels::text FROM task WHERE title = 'first'")).isEqualTo("1:{}");

                s.execute("INSERT INTO task(title) VALUES ('fresh')");
                assertThat(one(s, "SELECT title FROM task ORDER BY kanban_rank DESC LIMIT 1")).as("task mới ở cuối").isEqualTo("fresh");

                // COLLATE "C": so sánh theo byte, chữ HOA đứng trước chữ thường (locale en_US sẽ ngược lại)
                s.execute("UPDATE task SET kanban_rank = 'a' WHERE title = 'first'");
                s.execute("UPDATE task SET kanban_rank = 'B' WHERE title = 'second'");
                assertThat(one(s, "SELECT string_agg(title, ',' ORDER BY kanban_rank) FROM task WHERE title IN ('first','second')")).isEqualTo("second,first");
            }
        }
    }

    private static String one(Statement s, String sql) throws Exception {
        try (var rs = s.executeQuery(sql)) { rs.next(); return rs.getString(1); }
    }
}
