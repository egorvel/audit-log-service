package com.sam.auditlog.dto;

import com.sam.auditlog.model.Outcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record CreateAuditEventRequest(
        @NotBlank(message = "actor is required") String actor,
        @NotBlank(message = "action is required") String action,
        @NotBlank(message = "resource is required") String resource,
        @NotNull(message = "outcome is required") Outcome outcome,
        Map<String, Object> context
) {
}
