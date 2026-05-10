package com.sam.auditlog.service;

/**
 * Thrown when a client-supplied cursor cannot be decoded (malformed base64, invalid JSON, missing
 * required field). Mapped to HTTP 400 by {@code GlobalExceptionHandler}.
 */
public class CursorDecodeException extends IllegalArgumentException {

    public CursorDecodeException(String message) {
        super(message);
    }

    public CursorDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
