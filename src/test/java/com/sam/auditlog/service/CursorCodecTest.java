package com.sam.auditlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/** Pure unit test - no Spring context. */
class CursorCodecTest {

    private final CursorCodec codec = new CursorCodec();

    @Test
    void encode_decode_roundTripsAllFields() {
        Cursor original =
                new Cursor(
                        1,
                        Instant.parse("2026-04-25T10:00:00.123456Z"),
                        "01HE3XJ7N2K9V0R1B6T8Q4WMZ9",
                        "sha256:deadbeef");

        Cursor decoded = codec.decode(codec.encode(original));

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void decode_emptyOrBlank_throwsCursorDecodeException() {
        assertThatThrownBy(() -> codec.decode(""))
                .isInstanceOf(CursorDecodeException.class)
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> codec.decode("   ")).isInstanceOf(CursorDecodeException.class);
    }

    @Test
    void decode_malformedBase64_throwsCursorDecodeException() {
        assertThatThrownBy(() -> codec.decode("!!!not-base64!!!"))
                .isInstanceOf(CursorDecodeException.class)
                .hasMessageContaining("base64url");
    }

    @Test
    void decode_validBase64_butNotJson_throwsCursorDecodeException() {
        String token =
                Base64.getUrlEncoder().withoutPadding().encodeToString("not json".getBytes());

        assertThatThrownBy(() -> codec.decode(token))
                .isInstanceOf(CursorDecodeException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void decode_missingRequiredField_throwsCursorDecodeException() {
        // No "ts" field.
        String token =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString("{\"v\":1,\"id\":\"abc\",\"fh\":\"sha256:x\"}".getBytes());

        assertThatThrownBy(() -> codec.decode(token))
                .isInstanceOf(CursorDecodeException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void decode_unknownVersion_throwsQueryValidationException() {
        // v=2 is not the current schema version.
        Cursor wrong =
                new Cursor(
                        2,
                        Instant.parse("2026-04-25T10:00:00Z"),
                        "01HE3XJ7N2K9V0R1B6T8Q4WMZ9",
                        "sha256:x");
        String token = codec.encode(wrong);

        assertThatThrownBy(() -> codec.decode(token))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("v=2");
    }

    @Test
    void filterHash_isStableForEquivalentInputs() {
        Instant from = Instant.parse("2026-04-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-01T00:00:00Z");

        String a = codec.filterHash("u_42", "p_99", from, to);
        String b = codec.filterHash("u_42", "p_99", from, to);

        assertThat(a).isEqualTo(b).startsWith("sha256:");
    }

    @Test
    void filterHash_differsWhenAnyFieldDiffers() {
        Instant from = Instant.parse("2026-04-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-01T00:00:00Z");
        String base = codec.filterHash("u_42", "p_99", from, to);

        assertThat(codec.filterHash("u_43", "p_99", from, to)).isNotEqualTo(base);
        assertThat(codec.filterHash("u_42", "p_98", from, to)).isNotEqualTo(base);
        assertThat(codec.filterHash("u_42", "p_99", from.plusSeconds(1), to)).isNotEqualTo(base);
        assertThat(codec.filterHash("u_42", "p_99", from, to.plusSeconds(1))).isNotEqualTo(base);
    }

    @Test
    void filterHash_treatsNullAndEmptyConsistently() {
        Instant t = Instant.parse("2026-04-01T00:00:00Z");
        // null and "" must hash the same so a request that omits a filter hashes the same as
        // one that explicitly passes "" (the latter is rejected upstream by validation, but the
        // hash itself is just a function of the values it receives).
        String hashWithNullActor = codec.filterHash(null, "r", t, t);
        String hashWithEmptyActor = codec.filterHash("", "r", t, t);
        assertThat(hashWithNullActor).isEqualTo(hashWithEmptyActor);
    }

    @Test
    void filterHash_resistsAdjacencyCollision() {
        // Without a separator, hash("ab", "c") would collide with hash("a", "bc"). The unit
        // separator U+001F prevents this.
        String a = codec.filterHash("ab", "c", null, null);
        String b = codec.filterHash("a", "bc", null, null);
        assertThat(a).isNotEqualTo(b);
    }
}
