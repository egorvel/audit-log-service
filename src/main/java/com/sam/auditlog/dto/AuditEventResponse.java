package com.sam.auditlog.dto;

import java.time.Instant;
import java.util.Map;

import com.sam.auditlog.model.Outcome;

public record AuditEventResponse(
        long id,
        Instant timestamp,
        String actor,
        String action,
        String resource,
        Outcome outcome,
        Map<String, Object> context) {}
