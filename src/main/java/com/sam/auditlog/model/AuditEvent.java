package com.sam.auditlog.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable audit event. Once constructed, never mutated. timestamp is server-assigned by the
 * database (DEFAULT now()).
 */
public record AuditEvent(
        Long id,
        Instant timestamp,
        String actor,
        String action,
        String resource,
        Outcome outcome,
        Map<String, Object> context) {
    public AuditEvent {
        Objects.requireNonNull(actor, "actor is required");
        Objects.requireNonNull(action, "action is required");
        Objects.requireNonNull(resource, "resource is required");
        Objects.requireNonNull(outcome, "outcome is required");
        if (context == null) {
            context = Map.of();
        } else {
            context = Map.copyOf(context);
        }
    }
}
