package com.sam.auditlog.service;

import java.util.List;

/**
 * Thrown for any semantic rejection of an otherwise-parseable query: out-of-range limit, inverted
 * time range, empty filter strings, unknown cursor schema version, cursor filter-hash mismatch.
 * Mapped to HTTP 422 by {@code GlobalExceptionHandler}; carries field-level error messages
 * compatible with the existing {@code errors:[...]} response envelope.
 */
public class QueryValidationException extends RuntimeException {

    private final List<String> errors;

    public QueryValidationException(List<String> errors) {
        super(errors.isEmpty() ? "Validation failed" : String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public QueryValidationException(String error) {
        this(List.of(error));
    }

    public List<String> errors() {
        return errors;
    }
}
