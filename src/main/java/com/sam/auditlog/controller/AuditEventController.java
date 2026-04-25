package com.sam.auditlog.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.sam.auditlog.dto.AuditEventResponse;
import com.sam.auditlog.dto.CreateAuditEventRequest;
import com.sam.auditlog.service.AuditEventService;

@RestController
@RequestMapping("/api/v1/events")
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
                UriComponentsBuilder.fromPath("/api/v1/events/{id}")
                        .buildAndExpand(saved.id())
                        .toUri();
        return ResponseEntity.created(location).body(saved);
    }

    @GetMapping
    public ResponseEntity<List<AuditEventResponse>> list(
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.status(HttpStatus.OK).body(service.recent(limit));
    }
}
