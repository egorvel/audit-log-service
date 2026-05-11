package com.sam.auditlog.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.sam.auditlog.converter.AuditEventConverter;
import com.sam.auditlog.dto.AuditEventPage;
import com.sam.auditlog.dto.AuditEventResponse;
import com.sam.auditlog.model.AuditEvent;
import com.sam.auditlog.repository.AuditEventRepository;

/**
 * Read entry point for audit events. Centralizes semantic validation (per design §5.2) and the
 * keyset has-more decision (per design §3.4): the repository is asked for {@code limit + 1} rows;
 * if it returns exactly that many, the page is trimmed to {@code limit} and a {@code next_cursor}
 * is built from the limit-th row.
 */
@Service
public class AuditEventQueryService {

    /** Default page size when the caller omits {@code limit} (design §1.2 / AC3.7). */
    public static final int DEFAULT_LIMIT = 50;

    /** Inclusive bounds on {@code limit} (AC3.8b / design §5.2). */
    public static final int MIN_LIMIT = 1;

    public static final int MAX_LIMIT = 200;

    private final AuditEventRepository repository;
    private final AuditEventConverter converter;
    private final CursorCodec cursorCodec;

    public AuditEventQueryService(
            AuditEventRepository repository,
            AuditEventConverter converter,
            CursorCodec cursorCodec) {
        this.repository = repository;
        this.converter = converter;
        this.cursorCodec = cursorCodec;
    }

    public AuditEventPage query(QuerySpec spec) {
        int limit = spec.limit() == null ? DEFAULT_LIMIT : spec.limit();
        validate(spec, limit);

        var cursor = spec.cursor();
        var rows =
                repository.findPage(
                        spec.actor(),
                        spec.resource(),
                        spec.from(),
                        spec.to(),
                        cursor == null ? null : cursor.ts(),
                        cursor == null ? null : cursor.id(),
                        PageRequest.ofSize(limit + 1));

        boolean hasMore = rows.size() > limit;
        List<AuditEvent> pageRows = hasMore ? rows.subList(0, limit) : rows;

        List<AuditEventResponse> events = pageRows.stream().map(converter::toResponse).toList();

        String nextCursor = null;
        if (hasMore) {
            AuditEvent boundary = pageRows.get(pageRows.size() - 1);
            String fh =
                    cursorCodec.filterHash(spec.actor(), spec.resource(), spec.from(), spec.to());
            nextCursor =
                    cursorCodec.encode(
                            new Cursor(
                                    CursorCodec.CURRENT_VERSION,
                                    boundary.timestamp(),
                                    boundary.id(),
                                    fh));
        }
        return new AuditEventPage(events, nextCursor);
    }

    private void validate(QuerySpec spec, int effectiveLimit) {
        List<String> errors = new ArrayList<>();
        if (spec.from() != null && spec.to() != null && !spec.from().isBefore(spec.to())) {
            errors.add("from: must be strictly before to");
        }
        if (effectiveLimit < MIN_LIMIT || effectiveLimit > MAX_LIMIT) {
            errors.add("limit: must be between " + MIN_LIMIT + " and " + MAX_LIMIT);
        }
        if (spec.actor() != null && spec.actor().isBlank()) {
            errors.add("actor: must not be blank");
        }
        if (spec.resource() != null && spec.resource().isBlank()) {
            errors.add("resource: must not be blank");
        }
        if (spec.cursor() != null) {
            if (spec.cursor().v() != CursorCodec.CURRENT_VERSION) {
                errors.add("cursor: unsupported schema version v=" + spec.cursor().v());
            }
            String currentFh =
                    cursorCodec.filterHash(spec.actor(), spec.resource(), spec.from(), spec.to());
            if (!currentFh.equals(spec.cursor().fh())) {
                errors.add("cursor: does not match the current filter set");
            }
        }
        if (!errors.isEmpty()) {
            throw new QueryValidationException(errors);
        }
    }
}
