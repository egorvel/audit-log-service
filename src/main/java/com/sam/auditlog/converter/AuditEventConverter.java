package com.sam.auditlog.converter;

import com.sam.auditlog.dto.AuditEventResponse;
import com.sam.auditlog.dto.CreateAuditEventRequest;
import com.sam.auditlog.model.AuditEvent;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Pure mapper between API DTOs and the domain entity.
 * Note: timestamp is intentionally NOT taken from the request (server-assigned).
 */
@Component
public class AuditEventConverter {

    public AuditEvent toEntity(CreateAuditEventRequest request) {
        Map<String, Object> ctx = request.context() == null ? Map.of() : request.context();
        return new AuditEvent(
                null,
                null,
                request.actor(),
                request.action(),
                request.resource(),
                request.outcome(),
                ctx
        );
    }

    public AuditEventResponse toResponse(AuditEvent event) {
        return new AuditEventResponse(
                event.id(),
                event.timestamp(),
                event.actor(),
                event.action(),
                event.resource(),
                event.outcome(),
                event.context()
        );
    }
}
