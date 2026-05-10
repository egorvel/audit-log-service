package com.sam.auditlog.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * Immutable audit event. Once constructed, never mutated. timestamp is server-assigned by the
 * database (DEFAULT now()); id is a ULID assigned at the application layer.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 26, columnDefinition = "char(26)")
    private String id;

    @Generated(event = EventType.INSERT)
    @Column(name = "timestamp")
    private Instant timestamp;

    @Column(name = "actor_id", nullable = false)
    private String actorId;

    @Column(name = "actor_type", nullable = false)
    private String actorType;

    @Column(name = "resource_id", nullable = false)
    private String resourceId;

    @Column(name = "resource_type", nullable = false)
    private String resourceType;

    @Column(nullable = false)
    private String action;

    @Convert(converter = OutcomeConverter.class)
    @Column(nullable = false)
    private Outcome outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> context;

    protected AuditEvent() {
        // for JPA
    }

    public AuditEvent(
            String id,
            Instant timestamp,
            String actorId,
            String actorType,
            String resourceId,
            String resourceType,
            String action,
            Outcome outcome,
            Map<String, Object> context) {
        Objects.requireNonNull(actorId, "actor.id is required");
        Objects.requireNonNull(actorType, "actor.type is required");
        Objects.requireNonNull(resourceId, "resource.id is required");
        Objects.requireNonNull(resourceType, "resource.type is required");
        Objects.requireNonNull(action, "action is required");
        Objects.requireNonNull(outcome, "outcome is required");
        this.id = id;
        this.timestamp = timestamp;
        this.actorId = actorId;
        this.actorType = actorType;
        this.resourceId = resourceId;
        this.resourceType = resourceType;
        this.action = action;
        this.outcome = outcome;
        this.context = context == null ? Map.of() : Map.copyOf(context);
    }

    public String id() {
        return id;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public String actorId() {
        return actorId;
    }

    public String actorType() {
        return actorType;
    }

    public String resourceId() {
        return resourceId;
    }

    public String resourceType() {
        return resourceType;
    }

    public String action() {
        return action;
    }

    public Outcome outcome() {
        return outcome;
    }

    public Map<String, Object> context() {
        return context;
    }
}
