package com.sam.auditlog.dto;

import java.time.Instant;
import java.util.Map;

import com.sam.auditlog.model.Outcome;

public record AuditEventResponse(
        String id,
        Instant timestamp,
        ActorRef actor,
        ResourceRef resource,
        String action,
        Outcome outcome,
        Map<String, Object> context) {}
