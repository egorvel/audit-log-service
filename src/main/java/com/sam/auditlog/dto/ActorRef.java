package com.sam.auditlog.dto;

import jakarta.validation.constraints.NotBlank;

public record ActorRef(
        @NotBlank(message = "actor.id is required") String id,
        @NotBlank(message = "actor.type is required") String type) {}
