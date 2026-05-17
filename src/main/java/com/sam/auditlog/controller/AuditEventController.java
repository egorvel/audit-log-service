package com.sam.auditlog.controller;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

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
    @Operation(
            summary = "Record a new audit event",
            description =
                    "Persists an immutable audit event. The server assigns id (ULID) and"
                            + " timestamp; any client-supplied values for those fields are"
                            + " ignored.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Event created"),
        @ApiResponse(responseCode = "400", description = "Request body failed validation")
    })
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
    @Operation(
            summary = "List audit events with keyset pagination",
            description =
                    "Returns events ordered by (timestamp DESC, id DESC). Pages are opaque-cursor"
                            + " paginated; pass next_cursor from one response as cursor on the next"
                            + " request. The cursor encodes the originating filter set, so changing"
                            + " any filter while paging is rejected with 422.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of events"),
        @ApiResponse(
                responseCode = "400",
                description =
                        "Parse-tier failure: malformed timestamp, non-integer limit, or"
                                + " cursor that is not valid base64url(JSON)."),
        @ApiResponse(
                responseCode = "422",
                description =
                        "Semantic-tier failure: from >= to, limit outside [1, 200], blank"
                                + " filter values, cursor whose filter hash does not match the"
                                + " current request, or unsupported cursor schema version.")
    })
    public ResponseEntity<AuditEventPage> query(
            @Parameter(
                            description =
                                    "Filter by actor id. Comma-separated list of one or more"
                                        + " distinct ids (set membership). Duplicates are silently"
                                        + " dropped. Maximum 10 distinct ids per request.")
                    @RequestParam(required = false)
                    String actor,
            @Parameter(description = "Filter by resource id (exact match).")
                    @RequestParam(required = false)
                    String resource,
            @Parameter(description = "Inclusive lower bound on timestamp. RFC 3339 / ISO-8601.")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant from,
            @Parameter(description = "Exclusive upper bound on timestamp. RFC 3339 / ISO-8601.")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant to,
            @Parameter(
                            description =
                                    "Opaque pagination cursor from a previous response. Must be"
                                        + " replayed with the same filter set that produced it.")
                    @RequestParam(required = false)
                    String cursor,
            @Parameter(description = "Page size. Must be in [1, 200]. Defaults to 50.")
                    @RequestParam(required = false)
                    Integer limit) {
        Cursor decoded = cursor == null ? null : cursorCodec.decode(cursor);
        // Split with limit -1 so trailing/leading commas surface as blank entries (?actor=A, or
        // ?actor=,A) and the structural validator can reject them with 400 per AC1.12. A bare
        // ?actor= (key present, empty value) becomes a single blank entry and is rejected too.
        List<String> actorList = actor == null ? null : Arrays.asList(actor.split(",", -1));
        var canonicalActor = queryService.canonicalizeActor(actorList);
        var validatedResource = queryService.requireNonBlankResource(resource);
        return ResponseEntity.ok(
                queryService.query(
                        new QuerySpec(
                                canonicalActor,
                                validatedResource,
                                from,
                                to,
                                decoded,
                                limit)));
    }
}
