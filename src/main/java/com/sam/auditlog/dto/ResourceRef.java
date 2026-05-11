package com.sam.auditlog.dto;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Identifies the target object the event acted upon.")
public record ResourceRef(
        @Schema(description = "Stable identifier of the target object.")
                @NotBlank(message = "resource.id is required")
                String id,
        @Schema(description = "Kind of resource: \"project\", \"invoice\", etc.")
                @NotBlank(message = "resource.type is required")
                String type) {}
