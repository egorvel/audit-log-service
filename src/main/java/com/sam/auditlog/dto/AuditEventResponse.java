package com.sam.auditlog.dto;

import java.time.Instant;
import java.util.Map;

import com.sam.auditlog.model.Outcome;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A single audit event as returned by the API.")
public record AuditEventResponse(
        @Schema(description = "26-character Crockford-Base32 ULID assigned by the server.")
                String id,
        @Schema(description = "Server-assigned event time, RFC 3339 with microsecond precision.")
                Instant timestamp,
        ActorRef actor,
        ResourceRef resource,
        @Schema(description = "Domain action, e.g. \"user.login\" or \"resource.updated\".")
                String action,
        Outcome outcome,
        @Schema(description = "Free-form JSON details. May be empty but is never null.")
                Map<String, Object> context) {}
