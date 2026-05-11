package com.sam.auditlog.dto;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Identifies who initiated the event.")
public record ActorRef(
        @Schema(description = "Stable identifier (user id, service account id, etc.).")
                @NotBlank(message = "actor.id is required")
                String id,
        @Schema(description = "Kind of actor: \"user\", \"service\", etc.")
                @NotBlank(message = "actor.type is required")
                String type) {}
