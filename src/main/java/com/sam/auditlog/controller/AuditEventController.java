package com.sam.auditlog.controller;

import java.time.Instant;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.sam.auditlog.dto.AuditEventPage;
import com.sam.auditlog.dto.AuditEventResponse;
import com.sam.auditlog.dto.CreateAuditEventRequest;
import com.sam.auditlog.service.AuditEventQueryService;
import com.sam.auditlog.service.AuditEventService;
import com.sam.auditlog.service.Cursor;
import com.sam.auditlog.service.CursorCodec;
import com.sam.auditlog.service.QuerySpec;

@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditEventController {

    private final AuditEventService service;
    private final AuditEventQueryService queryService;
    private final CursorCodec cursorCodec;

    public AuditEventController(
            AuditEventService service,
            AuditEventQueryService queryService,
            CursorCodec cursorCodec) {
        this.service = service;
        this.queryService = queryService;
        this.cursorCodec = cursorCodec;
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

    @GetMapping
    public ResponseEntity<AuditEventPage> query(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        Cursor decoded = cursor == null ? null : cursorCodec.decode(cursor);
        return ResponseEntity.ok(
                queryService.query(new QuerySpec(actor, resource, from, to, decoded, limit)));
    }
}
