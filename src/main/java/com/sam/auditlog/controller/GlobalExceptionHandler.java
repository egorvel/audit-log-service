package com.sam.auditlog.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.sam.auditlog.service.CursorDecodeException;
import com.sam.auditlog.service.QueryValidationException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex) {
        List<String> errors =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                        .collect(Collectors.toList());
        return ResponseEntity.badRequest()
                .body(envelope(HttpStatus.BAD_REQUEST, "Validation failed", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(
            HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(envelope(HttpStatus.BAD_REQUEST, "Malformed request body", List.of()));
    }

    @ExceptionHandler(CursorDecodeException.class)
    public ResponseEntity<Map<String, Object>> handleCursorDecode(CursorDecodeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(
                        envelope(
                                HttpStatus.BAD_REQUEST,
                                "Cursor decode failed",
                                List.of(ex.getMessage())));
    }

    @ExceptionHandler(QueryValidationException.class)
    public ResponseEntity<Map<String, Object>> handleQueryValidation(QueryValidationException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(envelope(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed", ex.errors()));
    }

    private static Map<String, Object> envelope(
            HttpStatus status, String error, List<String> errors) {
        Map<String, Object> body =
                new java.util.LinkedHashMap<>(
                        Map.of(
                                "timestamp", Instant.now().toString(),
                                "status", status.value(),
                                "error", error));
        if (!errors.isEmpty()) {
            body.put("errors", errors);
        }
        return body;
    }
}
