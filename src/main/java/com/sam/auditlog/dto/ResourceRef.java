package com.sam.auditlog.dto;

import jakarta.validation.constraints.NotBlank;

public record ResourceRef(
        @NotBlank(message = "resource.id is required") String id,
        @NotBlank(message = "resource.type is required") String type) {}
