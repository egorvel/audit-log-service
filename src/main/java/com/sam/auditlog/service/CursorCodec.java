package com.sam.auditlog.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Encodes and decodes opaque pagination cursors (base64url(JSON)) and computes the filter-set hash
 * that binds a cursor to its originating request. Stateless and thread-safe; managed as a Spring
 * singleton but constructable directly (no DI dependencies of its own) so unit tests can {@code
 * new} an instance.
 *
 * <p>The cursor is intentionally NOT signed: it grants no privilege beyond what its filter set
 * implies, and the filter-hash check rejects tampered or cross-pasted cursors with HTTP 422.
 */
@Component
public class CursorCodec {

    /** Bumped only when the on-the-wire shape of {@link Cursor} changes incompatibly. */
    public static final int CURRENT_VERSION = 1;

    /** Unit separator (U+001F): not present in any sane filter value. */
    private static final String UNIT_SEPARATOR = "";

    private final ObjectMapper mapper;

    public CursorCodec() {
        this.mapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public String encode(Cursor cursor) {
        try {
            byte[] json = mapper.writeValueAsBytes(cursor);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to encode cursor", e);
        }
    }

    public Cursor decode(String token) {
        if (token == null || token.isBlank()) {
            throw new CursorDecodeException("cursor: empty token");
        }
        byte[] json;
        try {
            json = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            throw new CursorDecodeException("cursor: not valid base64url", e);
        }
        Cursor cursor;
        try {
            cursor = mapper.readValue(json, Cursor.class);
        } catch (IOException e) {
            throw new CursorDecodeException("cursor: not valid JSON", e);
        }
        if (cursor.ts() == null || cursor.id() == null || cursor.fh() == null) {
            throw new CursorDecodeException("cursor: missing required field");
        }
        if (cursor.v() != CURRENT_VERSION) {
            throw new QueryValidationException(
                    "cursor: unsupported schema version v=" + cursor.v());
        }
        return cursor;
    }

    /**
     * Hash of the filter set ({@code actor || resource || from || to}). Each field is rendered as
     * its string form ({@link Instant#toString} for instants), with {@code null}/missing as the
     * empty string. Fields are joined with U+001F so adjacent values cannot collide.
     */
    public String filterHash(String actor, String resource, Instant from, Instant to) {
        String joined =
                nullSafe(actor)
                        + UNIT_SEPARATOR
                        + nullSafe(resource)
                        + UNIT_SEPARATOR
                        + (from == null ? "" : from.toString())
                        + UNIT_SEPARATOR
                        + (to == null ? "" : to.toString());
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(joined.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
