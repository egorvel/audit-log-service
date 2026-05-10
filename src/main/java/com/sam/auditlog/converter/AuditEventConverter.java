package com.sam.auditlog.converter;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.sam.auditlog.dto.ActorRef;
import com.sam.auditlog.dto.AuditEventResponse;
import com.sam.auditlog.dto.CreateAuditEventRequest;
import com.sam.auditlog.dto.ResourceRef;
import com.sam.auditlog.model.AuditEvent;

/**
 * Pure mapper between API DTOs and the domain entity. The id is supplied by the caller (the service
 * generates a ULID via {@code UlidFactory}) so this layer stays Service-free per the package
 * layering rules. timestamp is intentionally NOT taken from the request (server-assigned).
 */
@Component
public class AuditEventConverter {

    public AuditEvent toEntity(CreateAuditEventRequest request, String id) {
        Map<String, Object> ctx = request.context() == null ? Map.of() : request.context();
        return new AuditEvent(
                id,
                null,
                request.actor().id(),
                request.actor().type(),
                request.resource().id(),
                request.resource().type(),
                request.action(),
                request.outcome(),
                ctx);
    }

    public AuditEventResponse toResponse(AuditEvent event) {
        return new AuditEventResponse(
                event.id(),
                event.timestamp(),
                new ActorRef(event.actorId(), event.actorType()),
                new ResourceRef(event.resourceId(), event.resourceType()),
                event.action(),
                event.outcome(),
                event.context());
    }
}
