package com.sam.auditlog.service;

import com.sam.auditlog.converter.AuditEventConverter;
import com.sam.auditlog.dto.AuditEventResponse;
import com.sam.auditlog.dto.CreateAuditEventRequest;
import com.sam.auditlog.repository.AuditEventRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuditEventService {

    private static final int MAX_PAGE_SIZE = 500;

    private final AuditEventRepository repository;
    private final AuditEventConverter converter;

    public AuditEventService(AuditEventRepository repository, AuditEventConverter converter) {
        this.repository = repository;
        this.converter = converter;
    }

    public AuditEventResponse record(CreateAuditEventRequest request) {
        var entity = converter.toEntity(request);
        var saved = repository.insert(entity);
        return converter.toResponse(saved);
    }

    public List<AuditEventResponse> recent(int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        return repository.findRecent(capped).stream()
                .map(converter::toResponse)
                .toList();
    }
}
