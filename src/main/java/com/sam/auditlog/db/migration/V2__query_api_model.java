package com.sam.auditlog.db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import com.github.f4b6a3.ulid.UlidCreator;

/**
 * V2 — replace the V1 schema with the query-api model: ULID primary key, structured actor/resource
 * columns. Implemented as a Java migration so the row backfill can call into `ulid-creator`
 * directly (the same library {@code UlidFactory} wraps), keeping a single ULID generator across
 * runtime and migration.
 *
 * <p>Strategy is INSERT-only into a fresh table followed by a DDL drop+rename — no UPDATE or DELETE
 * against live event rows (append-only invariant per AGENTS.md).
 */
public class V2__query_api_model extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();

        createNewTable(conn);
        backfillRows(conn);
        dropAndRename(conn);
        recreateTriggers(conn);
        regrant(conn);
        createIndexes(conn);
    }

    private static void createNewTable(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(
                    """
CREATE TABLE audit_events_new (
    id            CHAR(26)    PRIMARY KEY,
    timestamp     TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_id      TEXT        NOT NULL,
    actor_type    TEXT        NOT NULL,
    resource_id   TEXT        NOT NULL,
    resource_type TEXT        NOT NULL,
    action        TEXT        NOT NULL,
    outcome       TEXT        NOT NULL CHECK (outcome IN ('success', 'denied', 'error')),
    context       JSONB       NOT NULL DEFAULT '{}'::jsonb
)
""");
        }
    }

    private static void backfillRows(Connection conn) throws Exception {
        String selectSql =
                "SELECT timestamp, actor, action, resource, outcome, context FROM audit_events";
        String insertSql =
                "INSERT INTO audit_events_new"
                        + " (id, timestamp, actor_id, actor_type, resource_id, resource_type,"
                        + " action, outcome, context) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)";

        try (PreparedStatement select = conn.prepareStatement(selectSql);
                ResultSet rs = select.executeQuery();
                PreparedStatement insert = conn.prepareStatement(insertSql)) {
            int batched = 0;
            while (rs.next()) {
                Timestamp ts = rs.getTimestamp("timestamp");
                String ulid = UlidCreator.getUlid(ts.getTime()).toString();

                insert.setString(1, ulid);
                insert.setTimestamp(2, ts);
                insert.setString(3, rs.getString("actor"));
                insert.setString(4, "unknown");
                insert.setString(5, rs.getString("resource"));
                insert.setString(6, "unknown");
                insert.setString(7, rs.getString("action"));
                insert.setString(8, rs.getString("outcome"));
                insert.setString(9, rs.getString("context"));
                insert.addBatch();

                if (++batched >= 1000) {
                    insert.executeBatch();
                    batched = 0;
                }
            }
            if (batched > 0) {
                insert.executeBatch();
            }
        }
    }

    private static void dropAndRename(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE audit_events");
            stmt.execute("ALTER TABLE audit_events_new RENAME TO audit_events");
        }
    }

    private static void recreateTriggers(Connection conn) throws Exception {
        // The trigger function reject_audit_event_modification() was created in V1 and survives
        // the table drop, so we only need to recreate the triggers that bind it to the new table.
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "CREATE TRIGGER audit_events_no_update"
                            + " BEFORE UPDATE ON audit_events"
                            + " FOR EACH ROW EXECUTE FUNCTION reject_audit_event_modification()");
            stmt.execute(
                    "CREATE TRIGGER audit_events_no_delete"
                            + " BEFORE DELETE ON audit_events"
                            + " FOR EACH ROW EXECUTE FUNCTION reject_audit_event_modification()");
            stmt.execute(
                    "CREATE TRIGGER audit_events_no_truncate"
                            + " BEFORE TRUNCATE ON audit_events"
                            + " FOR EACH STATEMENT EXECUTE FUNCTION"
                            + " reject_audit_event_modification()");
        }
    }

    private static void regrant(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(
                    """
                    DO $$
                    BEGIN
                        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'audit_app') THEN
                            GRANT SELECT, INSERT ON audit_events TO audit_app;
                        END IF;
                    END $$
                    """);
        }
    }

    private static void createIndexes(Connection conn) throws Exception {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(
                    "CREATE INDEX idx_events_ts_id" + " ON audit_events (timestamp DESC, id DESC)");
            stmt.execute(
                    "CREATE INDEX idx_events_actor_ts_id"
                            + " ON audit_events (actor_id, timestamp DESC, id DESC)");
            stmt.execute(
                    "CREATE INDEX idx_events_res_ts_id"
                            + " ON audit_events (resource_id, timestamp DESC, id DESC)");
        }
    }
}
