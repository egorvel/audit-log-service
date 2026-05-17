package com.sam.auditlog.service;

import java.util.List;

/**
 * Thrown when a filter query parameter is present but structurally empty: {@code actor=} with no
 * value, an empty entry inside the comma-separated actor list (e.g. {@code actor=a1,,a2} or a
 * trailing {@code actor=a1,}), or {@code resource=} with no value. Mapped to HTTP 400 by {@code
 * GlobalExceptionHandler}; carries field-level error messages compatible with the existing {@code
 * errors:[...]} response envelope. See requirements §AC1.12 / §AC1.14 and design §1.4 / §5.2 for
 * the structural-vs-semantic split rationale.
 */
public class EmptyFilterException extends RuntimeException {

    private final List<String> errors;

    public EmptyFilterException(List<String> errors) {
        super(errors.isEmpty() ? "Empty filter value" : String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public EmptyFilterException(String error) {
        this(List.of(error));
    }

    public List<String> errors() {
        return errors;
    }
}
