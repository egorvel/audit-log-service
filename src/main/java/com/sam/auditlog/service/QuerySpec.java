package com.sam.auditlog.service;

import java.time.Instant;

/**
 * Internal value object carrying a decoded query request to {@link AuditEventQueryService}. Not a
 * DTO: the controller is responsible for binding HTTP parameters, decoding the opaque cursor via
 * {@link CursorCodec}, and assembling this record. Any field may be {@code null} (meaning "filter
 * absent") except where otherwise noted by the service's validation.
 */
public record QuerySpec(
        String actor, String resource, Instant from, Instant to, Cursor cursor, Integer limit) {}
