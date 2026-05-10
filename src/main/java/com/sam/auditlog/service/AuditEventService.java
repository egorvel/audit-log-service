package com.sam.auditlog.service;

import org.springframework.stereotype.Service;

import com.sam.auditlog.converter.AuditEventConverter;
import com.sam.auditlog.dto.AuditEventResponse;
import com.sam.auditlog.dto.CreateAuditEventRequest;
import com.sam.auditlog.repository.AuditEventRepository;

@Service
public class AuditEventService {

    private final AuditEventRepository repository;
    private final AuditEventConverter converter;
    private final UlidFactory ulidFactory;

    public AuditEventService(
            AuditEventRepository repository,
            AuditEventConverter converter,
            UlidFactory ulidFactory) {
        this.repository = repository;
        this.converter = converter;
        this.ulidFactory = ulidFactory;
    }

    public AuditEventResponse record(CreateAuditEventRequest request) {
        var entity = converter.toEntity(request, ulidFactory.next());
        var saved = repository.save(entity);
        return converter.toResponse(saved);
    }
}
