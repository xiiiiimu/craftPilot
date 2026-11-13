package com.craftpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class FlywayR1MigrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg16-trixie@sha256:9cf8b511f0e9a2c209b61430fc139f1ab9c75edbb31fb1751db6617b246a897a")
                    .asCompatibleSubstituteFor("postgres"));

    @Test
    void emptyDatabaseAppliesV1ThroughV5() {
        var flyway = flyway();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
        var jdbc = jdbc();
        assertThat(tableExists(jdbc, "principal")).isTrue();
        assertThat(tableExists(jdbc, "survey_session")).isTrue();
        assertThat(tableExists(jdbc, "outbox")).isTrue();
        assertThat(tableExists(jdbc, "message_attribution")).isTrue();
        assertThat(tableExists(jdbc, "evidence_chunk")).isTrue();
        assertThat(tableExists(jdbc, "agent_run")).isTrue();
        assertThat(tableExists(jdbc, "tool_invocation")).isTrue();
        assertThat(tableExists(jdbc, "checkpoint")).isTrue();
        assertThat(tableExists(jdbc, "skill_snapshot")).isTrue();
        assertThat(tableExists(jdbc, "handoff")).isTrue();
        assertThat(tableExists(jdbc, "task_join")).isTrue();
        assertThat(tableExists(jdbc, "connector_config")).isTrue();
        assertThat(tableExists(jdbc, "memory_entry")).isTrue();
        assertThat(tableExists(jdbc, "merchant_decision")).isTrue();
        assertThat(jdbc.sql("""
                SELECT version FROM craftpilot_app.flyway_schema_history
                WHERE success AND type = 'SQL' ORDER BY installed_rank
                """).query(String.class).list()).containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    void existingV3DatabaseUpgradesToV4RuntimeTables() {
        var throughV3 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("craftpilot_v3")
                .defaultSchema("craftpilot_v3")
                .createSchemas(true)
                .target("3")
                .load();
        assertThat(throughV3.migrate().migrationsExecuted).isEqualTo(3);
        var jdbc = jdbc("craftpilot_v3");
        assertThat(tableExists(jdbc, "craftpilot_v3", "evidence_ref")).isTrue();
        assertThat(tableExists(jdbc, "craftpilot_v3", "agent_run")).isFalse();

        var toV4 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("craftpilot_v3")
                .defaultSchema("craftpilot_v3")
                .target("4")
                .load();
        assertThat(toV4.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tableExists(jdbc, "craftpilot_v3", "agent_run")).isTrue();
        assertThat(tableExists(jdbc, "craftpilot_v3", "budget_reservation")).isTrue();
        assertThat(jdbc.sql("""
                SELECT version FROM craftpilot_v3.flyway_schema_history
                WHERE success AND type = 'SQL' ORDER BY installed_rank
                """).query(String.class).list()).containsExactly("1", "2", "3", "4");
    }

    @Test
    void existingV4DatabaseUpgradesToV5Extensions() {
        var throughV4 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("craftpilot_v4")
                .defaultSchema("craftpilot_v4")
                .createSchemas(true)
                .target("4")
                .load();
        assertThat(throughV4.migrate().migrationsExecuted).isEqualTo(4);
        var jdbc = jdbc("craftpilot_v4");
        assertThat(tableExists(jdbc, "craftpilot_v4", "agent_run")).isTrue();
        assertThat(tableExists(jdbc, "craftpilot_v4", "skill_snapshot")).isFalse();

        var toV5 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("craftpilot_v4")
                .defaultSchema("craftpilot_v4")
                .load();
        assertThat(toV5.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tableExists(jdbc, "craftpilot_v4", "skill_snapshot")).isTrue();
        assertThat(tableExists(jdbc, "craftpilot_v4", "handoff")).isTrue();
        assertThat(tableExists(jdbc, "craftpilot_v4", "memory_entry")).isTrue();
        assertThat(jdbc.sql("""
                SELECT version FROM craftpilot_v4.flyway_schema_history
                WHERE success AND type = 'SQL' ORDER BY installed_rank
                """).query(String.class).list()).containsExactly("1", "2", "3", "4", "5");
    }

    @Test
    void existingV1DatabaseUpgradesToV2WithTenantConstraints() {
        var step1 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("craftpilot_upgrade")
                .defaultSchema("craftpilot_upgrade")
                .createSchemas(true)
                .target("1")
                .load();
        assertThat(step1.migrate().migrationsExecuted).isEqualTo(1);
        var jdbc = jdbc("craftpilot_upgrade");
        assertThat(tableExists(jdbc, "craftpilot_upgrade", "principal")).isTrue();
        assertThat(tableExists(jdbc, "craftpilot_upgrade", "survey_session")).isFalse();

        var step2 = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("craftpilot_upgrade")
                .defaultSchema("craftpilot_upgrade")
                .load();
        assertThat(step2.migrate().migrationsExecuted).isEqualTo(4);
        assertThat(tableExists(jdbc, "craftpilot_upgrade", "survey_session")).isTrue();
        assertThat(tableExists(jdbc, "craftpilot_upgrade", "evidence_ref")).isTrue();
        assertThat(tableExists(jdbc, "craftpilot_upgrade", "agent_run")).isTrue();
        assertThat(tableExists(jdbc, "craftpilot_upgrade", "skill_snapshot")).isTrue();
        assertThat(jdbc.sql("""
                SELECT version FROM craftpilot_upgrade.flyway_schema_history
                WHERE success AND type = 'SQL' ORDER BY installed_rank
                """).query(String.class).list()).containsExactly("1", "2", "3", "4", "5");

        UUID merchantA = UUID.randomUUID();
        UUID merchantB = UUID.randomUUID();
        UUID principal = UUID.randomUUID();
        jdbc.sql("INSERT INTO craftpilot_upgrade.principal(id,login,display_name) VALUES (:id,'u','u')")
                .param("id", principal).update();
        jdbc.sql("INSERT INTO craftpilot_upgrade.merchant(id,name) VALUES (:a,'A'),(:b,'B')")
                .param("a", merchantA).param("b", merchantB).update();
        jdbc.sql("""
                INSERT INTO craftpilot_upgrade.merchant_member(merchant_id,principal_id,role_code)
                VALUES (:m,:p,'OWNER')
                """).param("m", merchantA).param("p", principal).update();

        UUID campaign = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO craftpilot_upgrade.launch_campaign(merchant_id,id,name,created_by)
                VALUES (:m,:id,'camp',:p)
                """).param("m", merchantA).param("id", campaign).param("p", principal).update();
        UUID style = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO craftpilot_upgrade.candidate_style(merchant_id,campaign_id,id,name,category)
                VALUES (:m,:c,:id,'style','cat')
                """).param("m", merchantA).param("c", campaign).param("id", style).update();
        UUID revision = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO craftpilot_upgrade.sample_revision
                (merchant_id,style_id,id,revision_no,snapshot_hash,created_by)
                VALUES (:m,:s,:id,1,'hash',:p)
                """).param("m", merchantA).param("s", style).param("id", revision).param("p", principal).update();

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO craftpilot_upgrade.sample_revision
                (merchant_id,style_id,id,revision_no,snapshot_hash,created_by)
                VALUES (:m,:s,:id,1,'hash2',:p)
                """).param("m", merchantA).param("s", style).param("id", UUID.randomUUID())
                .param("p", principal).update())
                .hasMessageContaining("sample_revision");

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO craftpilot_upgrade.bom_item
                (merchant_id,revision_id,line_no,material_code,material_name,quantity,unit,cost_gap)
                VALUES (:m,:r,1,'X','Y',1,'m',true)
                """).param("m", merchantB).param("r", revision).update())
                .hasMessageContaining("bom_item");

        UUID group = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO craftpilot_upgrade.craft_group(merchant_id,id,name) VALUES (:m,:id,'g')
                """).param("m", merchantA).param("id", group).update();
        jdbc.sql("""
                INSERT INTO craftpilot_upgrade.group_member(merchant_id,group_id,principal_id,role_code)
                VALUES (:m,:g,:p,'ADMIN')
                """).param("m", merchantA).param("g", group).param("p", principal).update();
        jdbc.sql("""
                INSERT INTO craftpilot_upgrade.survey_session
                (merchant_id,group_id,id,status,starts_at,planned_ends_at,intake_until,closing_deadline_at,created_by)
                VALUES (:m,:g,:id,'ACTIVE',clock_timestamp(),clock_timestamp()+interval '1 day',
                        clock_timestamp()+interval '1 day',clock_timestamp()+interval '1 day 10 minutes',:p)
                """).param("m", merchantA).param("g", group).param("id", UUID.randomUUID())
                .param("p", principal).update();
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO craftpilot_upgrade.survey_session
                (merchant_id,group_id,id,status,starts_at,planned_ends_at,intake_until,closing_deadline_at,created_by)
                VALUES (:m,:g,:id,'ACTIVE',clock_timestamp(),clock_timestamp()+interval '1 day',
                        clock_timestamp()+interval '1 day',clock_timestamp()+interval '1 day 10 minutes',:p)
                """).param("m", merchantA).param("g", group).param("id", UUID.randomUUID())
                .param("p", principal).update())
                .hasMessageContaining("survey_session_one_open_idx");
    }

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("craftpilot_app")
                .defaultSchema("craftpilot_app")
                .createSchemas(true)
                .load();
    }

    private static JdbcClient jdbc() {
        return jdbc("craftpilot_app");
    }

    private static JdbcClient jdbc(String schema) {
        String url = POSTGRES.getJdbcUrl();
        url = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
        var ds = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        return JdbcClient.create(ds);
    }

    private static boolean tableExists(JdbcClient jdbc, String table) {
        return tableExists(jdbc, "craftpilot_app", table);
    }

    private static boolean tableExists(JdbcClient jdbc, String schema, String table) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = :schema AND table_name = :table
                """).param("schema", schema).param("table", table).query(Long.class).single() > 0;
    }
}
