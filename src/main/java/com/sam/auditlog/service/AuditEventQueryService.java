package com.sam.auditlog.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    /** Per-request cap on distinct {@code actor} ids after dedup (AC1.13 / design §5.2). */
    public static final int MAX_DISTINCT_ACTORS = 10;

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
        if (spec.actor() != null && spec.actor().size() > MAX_DISTINCT_ACTORS) {
            errors.add(
                    "actor: at most "
                            + MAX_DISTINCT_ACTORS
                            + " distinct ids per request, got "
                            + spec.actor().size());
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

    /**
     * Canonicalize the raw actor list from the request: reject empty/blank entries with {@link
     * EmptyFilterException} (HTTP 400, requirements §AC1.12), then dedup into a {@link Set}
     * (requirements §AC1.11). Returns {@code null} when no actor filter is present so downstream
     * consumers treat it identically to the parameter being absent. The 10-distinct-id cap
     * (requirements §AC1.13) is checked downstream in {@link #validate} — this helper is the
     * structural (400) tier; the cap is semantic (422).
     */
    public Set<String> canonicalizeActor(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<String> badIndices = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String entry = raw.get(i);
            if (entry == null || entry.isBlank()) {
                badIndices.add("actor[" + i + "]: must not be blank");
            }
        }
        if (!badIndices.isEmpty()) {
            throw new EmptyFilterException(badIndices);
        }
        // LinkedHashSet preserves request order so error messages and debug logs are
        // reproducible; the cursor-hash canonicalization sorts at hash time regardless.
        return new LinkedHashSet<>(raw);
    }

    /**
     * Reject a present-but-blank {@code resource} value with {@link EmptyFilterException} (HTTP
     * 400, requirements §AC1.14). Returns the value unchanged when absent or non-blank.
     */
    public String requireNonBlankResource(String raw) {
        if (raw != null && raw.isBlank()) {
            throw new EmptyFilterException("resource: must not be blank");
        }
        return raw;
    }
}
