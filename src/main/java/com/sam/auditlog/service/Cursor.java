package com.sam.auditlog.service;

import java.time.Instant;

/** Decoded cursor: schema version, last-returned timestamp/id pair, and filter-set hash. */
public record Cursor(int v, Instant ts, String id, String fh) {}
