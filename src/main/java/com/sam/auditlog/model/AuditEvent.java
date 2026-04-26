package com.sam.auditlog.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * Immutable audit event. Once constructed, never mutated. timestamp is server-assigned by the
 * database (DEFAULT now()).
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Generated(event = EventType.INSERT)
    @Column(name = "timestamp")
    private Instant timestamp;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String resource;

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
            Long id,
            Instant timestamp,
            String actor,
            String action,
            String resource,
            Outcome outcome,
            Map<String, Object> context) {
        Objects.requireNonNull(actor, "actor is required");
        Objects.requireNonNull(action, "action is required");
        Objects.requireNonNull(resource, "resource is required");
        Objects.requireNonNull(outcome, "outcome is required");
        this.id = id;
        this.timestamp = timestamp;
        this.actor = actor;
        this.action = action;
        this.resource = resource;
        this.outcome = outcome;
        this.context = context == null ? Map.of() : Map.copyOf(context);
    }

    public Long id() {
        return id;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public String actor() {
        return actor;
    }

    public String action() {
        return action;
    }

    public String resource() {
        return resource;
    }

    public Outcome outcome() {
        return outcome;
    }

    public Map<String, Object> context() {
        return context;
    }
}
