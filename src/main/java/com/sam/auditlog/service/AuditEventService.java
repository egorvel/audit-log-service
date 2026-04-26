package com.sam.auditlog.service;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.sam.auditlog.converter.AuditEventConverter;
import com.sam.auditlog.dto.AuditEventResponse;
import com.sam.auditlog.dto.CreateAuditEventRequest;
import com.sam.auditlog.repository.AuditEventRepository;

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
        var saved = repository.save(entity);
        return converter.toResponse(saved);
    }

    public List<AuditEventResponse> recent(int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        return repository.findAllByOrderByIdDesc(PageRequest.of(0, capped)).stream()
                .map(converter::toResponse)
                .toList();
    }
}
