package com.sam.auditlog.dto;

import com.sam.auditlog.model.Outcome;

import java.time.Instant;
import java.util.Map;

public record AuditEventResponse(
        long id,
        Instant timestamp,
        String actor,
        String action,
        String resource,
        Outcome outcome,
        Map<String, Object> context
) {
}
