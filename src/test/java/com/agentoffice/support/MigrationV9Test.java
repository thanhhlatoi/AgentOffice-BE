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

/** V9 phải chạy được trên DB ĐÃ CÓ dữ liệu usage/giá bản rút gọn của V2 và giữ nguyên dữ liệu đó. */
class MigrationV9Test {
    private static final String A = "00000000-0000-0000-0000-000000000001";

    @Test
    void migratesExistingUsageDataSafely() throws Exception {
        try (var pg = new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))) {
            pg.start();
            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).target("8").load().migrate();
            try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); Statement s = c.createStatement()) {
                s.execute("INSERT INTO agent(id,name,role,department) VALUES ('" + A + "','A','r','d')");
                // hai bản giá cũ cùng model, cùng "mở" (V2 không có effective_to) => V9 phải đóng bản trước
                s.execute("INSERT INTO model_price(model,input_micro_per_mtok,output_micro_per_mtok,effective_from) VALUES "
                        + "('claude-haiku-5-5',1,1,'2025-01-01'),('claude-haiku-5-5',2,2,'2025-06-01')");
                s.execute("INSERT INTO usage_record(agent_id,model,input_tokens,output_tokens,cost_micro_usd,created_at) VALUES "
                        + "('" + A + "','claude-haiku-5-5',100,50,7,'2025-07-01T10:00:00Z')");
                s.execute("INSERT INTO usage_daily(day,agent_id,model,input_tokens,output_tokens,cost_micro_usd) VALUES "
                        + "('2025-07-01','" + A + "','claude-haiku-5-5',100,50,7)");

                Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load().migrate();

                assertThat(one(s, "SELECT prompt_tokens || ':' || completion_tokens || ':' || cost_micro_usd || ':' || status || ':' || priced FROM usage_record"))
                        .isEqualTo("100:50:7:OK:true");
                assertThat(one(s, "SELECT request_id IS NOT NULL FROM usage_record")).isEqualTo("t");
                assertThat(one(s, "SELECT calls || ':' || prompt_tokens || ':' || project_id FROM usage_daily"))
                        .isEqualTo("0:100:00000000-0000-0000-0000-000000000000");
                assertThat(one(s, "SELECT provider || ':' || (effective_to IS NOT NULL) FROM model_price WHERE input_micro_per_mtok = 1"))
                        .isEqualTo("anthropic:true");                              // provider suy từ model_profile, bản cũ đã đóng
                assertThat(one(s, "SELECT count(*) FROM model_price WHERE model = 'claude-haiku-5-5'")).isEqualTo("2");   // seed không chồng
                assertThat(one(s, "SELECT count(*) FROM model_price WHERE note LIKE 'GIÁ MẪU%'")).isEqualTo("2");
                // chồng khoảng hiệu lực bị DB chặn
                assertThatThrownBy(() -> s.execute("INSERT INTO model_price(provider,model,input_micro_per_mtok,output_micro_per_mtok,effective_from) "
                        + "VALUES ('anthropic','claude-haiku-5-5',9,9,'2025-08-01')")).hasMessageContaining("model_price_no_overlap");
                assertThatThrownBy(() -> s.execute("UPDATE model_price SET input_micro_per_mtok = 5 WHERE input_micro_per_mtok = 1"))
                        .hasMessageContaining("chỉ-thêm");
            }
        }
    }

    private static String one(Statement s, String sql) throws Exception {
        try (var rs = s.executeQuery(sql)) { rs.next(); return rs.getString(1); }
    }
}
