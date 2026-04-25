package com.sam.auditlog.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import com.sam.auditlog.model.Outcome;

public record CreateAuditEventRequest(
        @NotBlank(message = "actor is required") String actor,
        @NotBlank(message = "action is required") String action,
        @NotBlank(message = "resource is required") String resource,
        @NotNull(message = "outcome is required") Outcome outcome,
        Map<String, Object> context) {}
