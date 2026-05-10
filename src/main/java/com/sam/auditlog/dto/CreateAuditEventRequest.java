package com.sam.auditlog.dto;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.sam.auditlog.model.Outcome;

public record CreateAuditEventRequest(
        @NotNull(message = "actor is required") @Valid ActorRef actor,
        @NotBlank(message = "action is required") String action,
        @NotNull(message = "resource is required") @Valid ResourceRef resource,
        @NotNull(message = "outcome is required") Outcome outcome,
        Map<String, Object> context) {}
