package com.sam.auditlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Set;

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

        String a = codec.filterHash(Set.of("u_42"), "p_99", from, to);
        String b = codec.filterHash(Set.of("u_42"), "p_99", from, to);

        assertThat(a).isEqualTo(b).startsWith("sha256:");
    }

    @Test
    void filterHash_differsWhenAnyFieldDiffers() {
        Instant from = Instant.parse("2026-04-01T00:00:00Z");
        Instant to = Instant.parse("2026-05-01T00:00:00Z");
        String base = codec.filterHash(Set.of("u_42"), "p_99", from, to);

        assertThat(codec.filterHash(Set.of("u_43"), "p_99", from, to)).isNotEqualTo(base);
        assertThat(codec.filterHash(Set.of("u_42"), "p_98", from, to)).isNotEqualTo(base);
        assertThat(codec.filterHash(Set.of("u_42"), "p_99", from.plusSeconds(1), to))
                .isNotEqualTo(base);
        assertThat(codec.filterHash(Set.of("u_42"), "p_99", from, to.plusSeconds(1)))
                .isNotEqualTo(base);
    }

    @Test
    void filterHash_treatsNullAndEmptySetConsistently() {
        Instant t = Instant.parse("2026-04-01T00:00:00Z");
        // Both null and Set.of() mean "no actor filter" and must hash identically. The service
        // guarantees an empty set never reaches filterHash in practice (rejected upstream by
        // EmptyFilterException), but the codec defends symmetry anyway.
        String hashWithNullActor = codec.filterHash(null, "r", t, t);
        String hashWithEmptySet = codec.filterHash(Set.of(), "r", t, t);
        assertThat(hashWithNullActor).isEqualTo(hashWithEmptySet);
    }

    @Test
    void filterHash_isSetEqualAcrossOrderAndMultiplicity() {
        // Requirements §AC3.11: the cursor's filter hash treats the actor list as a set, so a
        // replay request that submits the same ids in any order (and any multiplicity, since
        // duplicates are deduped upstream into a Set) reproduces the same hash.
        Instant t = Instant.parse("2026-04-01T00:00:00Z");
        String ab = codec.filterHash(Set.of("a", "b"), "r", t, t);
        String ba = codec.filterHash(Set.of("b", "a"), "r", t, t);
        // LinkedHashSet preserves insertion order; both still sort identically inside filterHash.
        Set<String> ordered = new LinkedHashSet<>();
        ordered.add("b");
        ordered.add("a");
        String orderedHash = codec.filterHash(ordered, "r", t, t);

        assertThat(ab).isEqualTo(ba).isEqualTo(orderedHash);
    }

    @Test
    void filterHash_differsByOneIdInSet() {
        Instant t = Instant.parse("2026-04-01T00:00:00Z");
        String ab = codec.filterHash(Set.of("a", "b"), "r", t, t);
        String abc = codec.filterHash(Set.of("a", "b", "c"), "r", t, t);

        assertThat(ab).isNotEqualTo(abc);
    }

    @Test
    void filterHash_resistsAdjacencyCollision() {
        // Without a separator, hash("ab", "c") would collide with hash("a", "bc"). The unit
        // separator U+001F prevents this. The same logic applies inside the multi-actor
        // segment: {"ab"} || "c" vs {"a"} || "bc" must not collide.
        String a = codec.filterHash(Set.of("ab"), "c", null, null);
        String b = codec.filterHash(Set.of("a"), "bc", null, null);
        assertThat(a).isNotEqualTo(b);

        // And inside the actor segment itself: {"ab","c"} (joined "abc") vs
        // {"a","bc"} (joined "abc") must not collide because the inner separator
        // sits at different positions.
        String c = codec.filterHash(Set.of("ab", "c"), "r", null, null);
        String d = codec.filterHash(Set.of("a", "bc"), "r", null, null);
        assertThat(c).isNotEqualTo(d);
    }
}
