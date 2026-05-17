package com.sam.auditlog.service;

import java.time.Instant;
import java.util.Set;

/**
 * Internal value object carrying a decoded query request to {@link AuditEventQueryService}. Not a
 * DTO: the controller is responsible for binding HTTP parameters, decoding the opaque cursor via
 * {@link CursorCodec}, and assembling this record. Any field may be {@code null} (meaning "filter
 * absent") except where otherwise noted by the service's validation.
 *
 * <p>{@code actor} is held as a {@link Set} so the dedup mandated by requirements §AC1.11 is
 * structurally permanent: every downstream consumer (validation cap, repository {@code IN}-list,
 * cursor-hash recomputation) sees the same canonical form. The set is produced by {@link
 * AuditEventQueryService#canonicalizeActor(java.util.List)} from the raw {@code List<String>}
 * Spring binds out of the request.
 */
public record QuerySpec(
        Set<String> actor,
        String resource,
        Instant from,
        Instant to,
        Cursor cursor,
        Integer limit) {}
