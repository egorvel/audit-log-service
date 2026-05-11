package com.sam.auditlog.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A single page of audit events with an optional cursor to the next page.")
public record AuditEventPage(
        @Schema(description = "Events in the page, ordered by (timestamp DESC, id DESC).")
                List<AuditEventResponse> events,
        @Schema(
                        description =
                                "Opaque cursor to fetch the next page. Absent on the final page;"
                                        + " must be replayed with the same filters that produced"
                                        + " it.")
                @JsonProperty("next_cursor")
                @JsonInclude(JsonInclude.Include.NON_NULL)
                String nextCursor) {}
