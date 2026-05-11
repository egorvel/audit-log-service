package com.sam.auditlog.dto;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.sam.auditlog.model.Outcome;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Body for POST /api/v1/audit-events.")
public record CreateAuditEventRequest(
        @NotNull(message = "actor is required") @Valid ActorRef actor,
        @Schema(description = "Domain action, e.g. \"user.login\" or \"resource.updated\".")
                @NotBlank(message = "action is required")
                String action,
        @NotNull(message = "resource is required") @Valid ResourceRef resource,
        @NotNull(message = "outcome is required") Outcome outcome,
        @Schema(description = "Optional free-form JSON details.") Map<String, Object> context) {}
