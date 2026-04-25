package com.sam.auditlog.repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.sam.auditlog.model.AuditEvent;
import com.sam.auditlog.model.Outcome;
import com.sam.auditlog.util.JsonSupport;

@Repository
public class AuditEventRepository {

    private static final String INSERT_SQL =
            """
            INSERT INTO audit_events (actor, action, resource, outcome, context)
            VALUES (?, ?, ?, ?, ?::jsonb)
            """;

    private static final String SELECT_RECENT_SQL =
            """
            SELECT id, timestamp, actor, action, resource, outcome, context
            FROM audit_events
            ORDER BY id DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    private final JsonSupport json;

    public AuditEventRepository(JdbcTemplate jdbc, JsonSupport json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public AuditEvent insert(AuditEvent event) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(
                connection -> {
                    PreparedStatement ps =
                            connection.prepareStatement(
                                    INSERT_SQL, Statement.RETURN_GENERATED_KEYS);
                    ps.setString(1, event.actor());
                    ps.setString(2, event.action());
                    ps.setString(3, event.resource());
                    ps.setString(4, event.outcome().dbValue());
                    ps.setString(5, json.writeMap(event.context()));
                    return ps;
                },
                keyHolder);

        var keys = keyHolder.getKeys();
        if (keys == null) {
            throw new IllegalStateException("Insert did not return generated keys");
        }
        long id = ((Number) keys.get("id")).longValue();
        Timestamp ts = (Timestamp) keys.get("timestamp");
        return new AuditEvent(
                id,
                ts.toInstant(),
                event.actor(),
                event.action(),
                event.resource(),
                event.outcome(),
                event.context());
    }

    public List<AuditEvent> findRecent(int limit) {
        return jdbc.query(SELECT_RECENT_SQL, rowMapper(), limit);
    }

    private RowMapper<AuditEvent> rowMapper() {
        return (rs, rowNum) ->
                new AuditEvent(
                        rs.getLong("id"),
                        rs.getTimestamp("timestamp").toInstant(),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("resource"),
                        Outcome.fromDb(rs.getString("outcome")),
                        json.readMap(extractJson(rs.getObject("context"))));
    }

    private static String extractJson(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        // PGobject is on the runtime classpath only; we use it via reflection on toString().
        // PGobject.toString() returns its value, which is the JSON text we need.
        return value.toString();
    }
}
