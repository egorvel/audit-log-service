package com.sam.auditlog.controller;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.sam.auditlog.dto.AuditEventResponse;
import com.sam.auditlog.dto.CreateAuditEventRequest;
import com.sam.auditlog.service.AuditEventService;

@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditEventController {

    private final AuditEventService service;

    public AuditEventController(AuditEventService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AuditEventResponse> create(
            @Valid @RequestBody CreateAuditEventRequest request) {
        AuditEventResponse saved = service.record(request);
        var location =
                UriComponentsBuilder.fromPath("/api/v1/audit-events/{id}")
                        .buildAndExpand(saved.id())
                        .toUri();
        return ResponseEntity.created(location).body(saved);
    }
}
