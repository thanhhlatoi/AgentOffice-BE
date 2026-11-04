package com.agentoffice.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** V7 phải chạy được trên DB ĐÃ CÓ dữ liệu V1..V6 và giữ nguyên dữ liệu cũ. */
class MigrationV7Test {
    @Test
    void migratesExistingDataSafely() throws Exception {
        try (var pg = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))) {
            pg.start();
            Flyway old = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).target("6").load();
            old.migrate();
            try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
                s.execute("INSERT INTO agent(id,name,role,department) VALUES ('00000000-0000-0000-0000-000000000001','A','r','d')");
                s.execute("INSERT INTO goal(id,title,status) VALUES ('00000000-0000-0000-0000-0000000000a1','g1','ACTIVE'),('00000000-0000-0000-0000-0000000000a2','g2','DONE')");
                s.execute("INSERT INTO task(id,goal_id,assignee_id,title,instruction,attempt) VALUES ('00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-000000000001','t','do it',2)");
                s.execute("INSERT INTO approval(task_id,agent_id,tool_name,risk_level,args_snapshot,idempotency_key,expires_at) VALUES ('00000000-0000-0000-0000-0000000000b1','00000000-0000-0000-0000-000000000001','x','EXTERNAL','{}','k1',now())");
                s.execute("INSERT INTO approval_policy(risk_level,mode) VALUES ('EXTERNAL','AUTO')");   // dữ liệu cũ: AUTO

                Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();

                assertThat(one(s, "SELECT string_agg(status, ',' ORDER BY title) FROM goal")).isEqualTo("RUNNING,SUCCEEDED");
                assertThat(one(s, "SELECT input || ':' || attempts || ':' || created_by FROM task")).isEqualTo("do it:2:HUMAN");
                assertThat(one(s, "SELECT kind FROM approval")).isEqualTo("TOOL_CALL");
                // mọi giá trị enum phải nhét vừa cột (bài học: AWAITING_PLAN_APPROVAL > 20 ký tự)
                for (String g : new String[] {"DRAFT","PLANNING","AWAITING_PLAN_APPROVAL","RUNNING","SUCCEEDED","FAILED","CANCELLED"})
                    s.execute("INSERT INTO goal(title,status) VALUES ('x','" + g + "')");
                for (String t : new String[] {"PENDING","BLOCKED","QUEUED","RUNNING","WAITING_APPROVAL","WAITING_BUDGET","SUCCEEDED","FAILED","CANCELLED"})
                    s.execute("INSERT INTO task(goal_id,assignee_agent_id,title,status) VALUES ('00000000-0000-0000-0000-0000000000a1','00000000-0000-0000-0000-000000000001','x','" + t + "')");
                // MONEY/DESTRUCTIVE không thể tắt duyệt, kể cả ở tầng DB
                assertThatThrownBy(() -> s.execute("UPDATE approval_policy SET requires_approval=FALSE WHERE risk_level='MONEY'"))
                        .hasMessageContaining("approval_policy_hard_chk");
                // audit chỉ thêm
                s.execute("INSERT INTO approval_audit(approval_id,actor,action) SELECT id,'t','REQUESTED' FROM approval");
                assertThatThrownBy(() -> s.execute("UPDATE approval_audit SET actor='x'")).hasMessageContaining("append-only");
                assertThatThrownBy(() -> s.execute("DELETE FROM approval_audit")).hasMessageContaining("append-only");
                assertThatThrownBy(() -> s.execute("TRUNCATE approval_audit")).hasMessageContaining("append-only");
            }
        }
    }

    private static String one(Statement s, String sql) throws Exception {
        try (var rs = s.executeQuery(sql)) { rs.next(); return rs.getString(1); }
    }
}
