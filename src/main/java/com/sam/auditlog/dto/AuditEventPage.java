package com.sam.auditlog.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire representation of a single page of audit events. {@code nextCursor} is omitted from JSON
 * when {@code null} (i.e. on the final page).
 */
public record AuditEventPage(
        List<AuditEventResponse> events,
        @JsonProperty("next_cursor") @JsonInclude(JsonInclude.Include.NON_NULL)
                String nextCursor) {}
