package com.sam.auditlog.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.f4b6a3.ulid.Ulid;
import com.github.f4b6a3.ulid.UlidCreator;

/**
 * Verifies V2's table-replacement migration. Each test starts from a clean Flyway baseline so the
 * Postgres container can be shared across tests without state leakage.
 */
@Testcontainers
class V2MigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.3-alpine")
                    .withDatabaseName("auditlog")
                    .withUsername("test")
                    .withPassword("test");

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @BeforeEach
    void resetSchema() {
        flyway(null).clean();
    }

    @Test
    void v2_migrates_v1_rows_with_ulid_ids_and_unknown_types() throws Exception {
        flyway("1").migrate();

        List<SeedRow> seeded;
        try (Connection conn = openConnection()) {
            seeded = seedV1Rows(conn);
        }

        flyway("2").migrate();

        try (Connection conn = openConnection()) {
            assertThat(rowCount(conn)).isEqualTo(seeded.size());

            try (Statement s = conn.createStatement();
                    ResultSet rs =
                            s.executeQuery(
                                    "SELECT id, timestamp, actor_id, actor_type, resource_id,"
                                            + " resource_type FROM audit_events ORDER BY id"
                                            + " ASC")) {
                int idx = 0;
                Instant prev = null;
                while (rs.next()) {
                    String id = rs.getString("id").trim();
                    assertThat(id).hasSize(26);

                    Ulid parsed = Ulid.from(id);
                    Instant ts = rs.getTimestamp("timestamp").toInstant();
                    assertThat(parsed.getTime()).isEqualTo(ts.toEpochMilli());
                    if (prev != null) {
                        assertThat(ts).isAfterOrEqualTo(prev);
                    }
                    prev = ts;

                    assertThat(rs.getString("actor_type")).isEqualTo("unknown");
                    assertThat(rs.getString("resource_type")).isEqualTo("unknown");
                    assertThat(rs.getString("actor_id")).isEqualTo(seeded.get(idx).actor());
                    assertThat(rs.getString("resource_id")).isEqualTo(seeded.get(idx).resource());
                    idx++;
                }
                assertThat(idx).isEqualTo(seeded.size());
            }

            assertTriggersBlockMutation(conn);
            assertThat(indexNames(conn))
                    .contains("idx_events_ts_id", "idx_events_actor_ts_id", "idx_events_res_ts_id");
        }
    }

    @Test
    void v2_on_empty_v1_table_creates_clean_v2_schema() throws Exception {
        flyway("1").migrate();
        // No seeding.
        flyway("2").migrate();

        try (Connection conn = openConnection()) {
            assertThat(rowCount(conn)).isZero();
            assertThat(indexNames(conn))
                    .contains("idx_events_ts_id", "idx_events_actor_ts_id", "idx_events_res_ts_id");
            assertTriggersBlockMutation(conn);
        }
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** {@code target == null} means "all migrations". */
    private static Flyway flyway(String target) {
        var cfg =
                Flyway.configure()
                        .dataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword())
                        .locations(
                                "classpath:db/migration", "classpath:com/sam/auditlog/db/migration")
                        .cleanDisabled(false);
        if (target != null) {
            cfg = cfg.target(target);
        }
        return cfg.load();
    }

    private static List<SeedRow> seedV1Rows(Connection conn) throws SQLException {
        List<SeedRow> rows = new ArrayList<>();
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO audit_events (timestamp, actor, action, resource, outcome,"
                                + " context) VALUES (?, ?, ?, ?, ?, ?::jsonb)")) {
            for (int i = 0; i < 10; i++) {
                Instant ts = base.plusSeconds(i);
                String actor = "actor_" + i;
                String resource = "resource_" + i;
                ps.setTimestamp(1, Timestamp.from(ts));
                ps.setString(2, actor);
                ps.setString(3, "act_" + i);
                ps.setString(4, resource);
                ps.setString(5, "success");
                ps.setString(6, "{\"k\":" + i + "}");
                ps.executeUpdate();
                rows.add(new SeedRow(ts, actor, resource));
            }
        }
        return rows;
    }

    private static long rowCount(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM audit_events")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static List<String> indexNames(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement();
                ResultSet rs =
                        s.executeQuery(
                                "SELECT indexname FROM pg_indexes WHERE tablename ="
                                        + " 'audit_events'")) {
            List<String> names = new ArrayList<>();
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            return names;
        }
    }

    private static void assertTriggersBlockMutation(Connection conn) throws SQLException {
        String ulid = UlidCreator.getMonotonicUlid().toString();
        try (PreparedStatement ps =
                conn.prepareStatement(
                        "INSERT INTO audit_events (id, actor_id, actor_type, action, resource_id,"
                                + " resource_type, outcome) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, ulid);
            ps.setString(2, "actor");
            ps.setString(3, "user");
            ps.setString(4, "act");
            ps.setString(5, "res");
            ps.setString(6, "thing");
            ps.setString(7, "success");
            ps.executeUpdate();
        }

        assertThatThrownBy(
                        () -> {
                            try (Statement s = conn.createStatement()) {
                                s.executeUpdate(
                                        "UPDATE audit_events SET actor_id = 'hacker' WHERE id = '"
                                                + ulid
                                                + "'");
                            }
                        })
                .hasMessageContaining("append-only");

        assertThatThrownBy(
                        () -> {
                            try (Statement s = conn.createStatement()) {
                                s.executeUpdate("DELETE FROM audit_events");
                            }
                        })
                .hasMessageContaining("append-only");

        assertThatThrownBy(
                        () -> {
                            try (Statement s = conn.createStatement()) {
                                s.execute("TRUNCATE audit_events");
                            }
                        })
                .hasMessageContaining("append-only");
    }

    private record SeedRow(Instant timestamp, String actor, String resource) {}
}
