package com.sam.auditlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import com.sam.auditlog.converter.AuditEventConverter;
import com.sam.auditlog.dto.AuditEventPage;
import com.sam.auditlog.model.AuditEvent;
import com.sam.auditlog.model.Outcome;
import com.sam.auditlog.repository.AuditEventRepository;

/**
 * Unit test for the query service. Repository is mocked; {@link CursorCodec} and {@link
 * AuditEventConverter} are real (both are pure helpers with no DI).
 */
@ExtendWith(MockitoExtension.class)
class AuditEventQueryServiceTest {

    @Mock AuditEventRepository repository;

    private final CursorCodec codec = new CursorCodec();
    private final AuditEventConverter converter = new AuditEventConverter();

    private AuditEventQueryService service;

    private final Instant t0 = Instant.parse("2026-04-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        service = new AuditEventQueryService(repository, converter, codec);
    }

    // ----- semantic validation (422) -----

    @Test
    void query_fromNotBeforeTo_throws422() {
        QuerySpec spec = new QuerySpec(null, null, t0.plusSeconds(60), t0, null, 10);
        assertThatThrownBy(() -> service.query(spec))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("from");
    }

    @Test
    void query_fromEqualsTo_throws422() {
        // Half-open window collapses to empty: rejected as inverted.
        QuerySpec spec = new QuerySpec(null, null, t0, t0, null, 10);
        assertThatThrownBy(() -> service.query(spec))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("from");
    }

    @Test
    void query_limitTooSmall_throws422() {
        QuerySpec spec = new QuerySpec(null, null, null, null, null, 0);
        assertThatThrownBy(() -> service.query(spec))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void query_limitTooLarge_throws422() {
        QuerySpec spec = new QuerySpec(null, null, null, null, null, 201);
        assertThatThrownBy(() -> service.query(spec))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("limit");
    }

    // ----- structural rejections (400) — actor/resource empty/blank are now upstream of validate -----

    @Test
    void canonicalizeActor_blankEntry_throws400() {
        // AC1.12: any blank entry in the comma-separated list is a structural failure → 400.
        assertThatThrownBy(() -> service.canonicalizeActor(List.of("a1", "   ", "a2")))
                .isInstanceOf(EmptyFilterException.class)
                .hasMessageContaining("actor[1]");
    }

    @Test
    void canonicalizeActor_singleEmptyEntry_throws400() {
        // ?actor= binds in Spring as [""]; one bad entry, one 400.
        assertThatThrownBy(() -> service.canonicalizeActor(List.of("")))
                .isInstanceOf(EmptyFilterException.class)
                .hasMessageContaining("actor[0]");
    }

    @Test
    void canonicalizeActor_nullOrEmptyList_returnsNull() {
        assertThat(service.canonicalizeActor(null)).isNull();
        assertThat(service.canonicalizeActor(List.of())).isNull();
    }

    @Test
    void canonicalizeActor_dedup_returnsDistinctSet() {
        // AC1.11: duplicates collapse silently.
        Set<String> result = service.canonicalizeActor(List.of("a", "a", "b"));
        assertThat(result).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void canonicalizeActor_atCap_doesNotThrow() {
        // The 10-distinct cap is a downstream concern of validate(); canonicalize never throws on
        // size, so an 11-distinct list returns an 11-element set and the cap is enforced later.
        Set<String> result =
                service.canonicalizeActor(
                        List.of("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10", "a11"));
        assertThat(result).hasSize(11);
    }

    @Test
    void requireNonBlankResource_blank_throws400() {
        // AC1.14: resource present but empty/blank is a structural failure → 400.
        assertThatThrownBy(() -> service.requireNonBlankResource(""))
                .isInstanceOf(EmptyFilterException.class)
                .hasMessageContaining("resource");
        assertThatThrownBy(() -> service.requireNonBlankResource("   "))
                .isInstanceOf(EmptyFilterException.class);
    }

    @Test
    void requireNonBlankResource_nullOrPresent_passesThrough() {
        assertThat(service.requireNonBlankResource(null)).isNull();
        assertThat(service.requireNonBlankResource("p_42")).isEqualTo("p_42");
    }

    @Test
    void query_actorSetOver10_throws422() {
        // AC1.13: more than 10 distinct ids after dedup → 422.
        Set<String> eleven =
                Set.of("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10", "a11");
        QuerySpec spec = new QuerySpec(eleven, null, null, null, null, 10);

        assertThatThrownBy(() -> service.query(spec))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("at most 10");
    }

    @Test
    void query_cursorFhMismatch_throws422() {
        // Cursor was encoded with filter actor={alice}; current request has actor={bob} -> mismatch.
        Cursor encodedWithAlice =
                new Cursor(
                        CursorCodec.CURRENT_VERSION,
                        t0.plusSeconds(5),
                        "01HE3XJ7N2K9V0R1B6T8Q4WMZ9",
                        codec.filterHash(Set.of("alice"), null, null, null));
        QuerySpec spec = new QuerySpec(Set.of("bob"), null, null, null, encodedWithAlice, 10);

        assertThatThrownBy(() -> service.query(spec))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("filter set");
    }

    @Test
    void query_cursorFhMatchesAcrossReorderedActorSet() {
        // AC3.11: cursor minted under {a,b} accepted when replayed with {b,a}.
        when(repository.findPage(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        Cursor mintedWithAB =
                new Cursor(
                        CursorCodec.CURRENT_VERSION,
                        t0.plusSeconds(5),
                        "01HE3XJ7N2K9V0R1B6T8Q4WMZ9",
                        codec.filterHash(Set.of("a", "b"), null, null, null));
        Set<String> reordered = new LinkedHashSet<>();
        reordered.add("b");
        reordered.add("a");
        QuerySpec spec = new QuerySpec(reordered, null, null, null, mintedWithAB, 10);

        // No exception thrown — the page is empty but validation passes.
        AuditEventPage page = service.query(spec);
        assertThat(page.events()).isEmpty();
    }

    @Test
    void query_cursorFhMismatchOnDifferentActorSet() {
        // AC3.11 negative case: a one-id difference in the set → 422.
        Cursor mintedWithAB =
                new Cursor(
                        CursorCodec.CURRENT_VERSION,
                        t0.plusSeconds(5),
                        "01HE3XJ7N2K9V0R1B6T8Q4WMZ9",
                        codec.filterHash(Set.of("a", "b"), null, null, null));
        QuerySpec spec = new QuerySpec(Set.of("a"), null, null, null, mintedWithAB, 10);

        assertThatThrownBy(() -> service.query(spec))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("filter set");
    }

    // ----- has-more boundary -----

    @Test
    void query_repoReturnsExactlyLimit_omitsNextCursor() {
        int limit = 3;
        when(repository.findPage(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(seedEvents(limit));

        AuditEventPage page = service.query(new QuerySpec(null, null, null, null, null, limit));

        assertThat(page.events()).hasSize(limit);
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void query_repoReturnsLimitPlusOne_buildsCursorFromLimitthRow() {
        int limit = 3;
        List<AuditEvent> rows = seedEvents(limit + 1);
        when(repository.findPage(any(), any(), any(), any(), any(), any(), any())).thenReturn(rows);

        AuditEventPage page =
                service.query(new QuerySpec(Set.of("a"), null, null, null, null, limit));

        assertThat(page.events()).hasSize(limit);
        assertThat(page.nextCursor()).isNotNull();

        Cursor decoded = codec.decode(page.nextCursor());
        AuditEvent boundary = rows.get(limit - 1);
        assertThat(decoded.ts()).isEqualTo(boundary.timestamp());
        assertThat(decoded.id()).isEqualTo(boundary.id());
        assertThat(decoded.fh()).isEqualTo(codec.filterHash(Set.of("a"), null, null, null));
        assertThat(decoded.v()).isEqualTo(CursorCodec.CURRENT_VERSION);
    }

    @Test
    void query_repoReturnsEmpty_omitsNextCursor() {
        when(repository.findPage(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        // Cursor must match the (null,null,null,null) filter set so semantic validation passes.
        Cursor cursorWithMatchingFh =
                new Cursor(
                        CursorCodec.CURRENT_VERSION,
                        t0.plusSeconds(10),
                        "01HE3XJ7N2K9V0R1B6T8Q4WMZ9",
                        codec.filterHash(null, null, null, null));
        AuditEventPage page =
                service.query(new QuerySpec(null, null, null, null, cursorWithMatchingFh, 10));

        assertThat(page.events()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    // ----- default limit -----

    @Test
    void query_nullLimit_usesDefaultFiftyAndAsksForFiftyOne() {
        when(repository.findPage(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        service.query(new QuerySpec(null, null, null, null, null, null));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository)
                .findPage(
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(null),
                        eq(null),
                        pageable.capture());
        assertThat(pageable.getValue().getPageSize())
                .isEqualTo(AuditEventQueryService.DEFAULT_LIMIT + 1);
    }

    // ----- helpers -----

    private List<AuditEvent> seedEvents(int n) {
        return IntStream.range(0, n)
                .mapToObj(
                        i ->
                                new AuditEvent(
                                        "01HE3XJ7N2K9V0R1B6T8Q4WMZ" + i,
                                        t0.plusSeconds(i),
                                        "actor",
                                        "user",
                                        "resource",
                                        "project",
                                        "act",
                                        Outcome.SUCCESS,
                                        Map.of()))
                .toList();
    }
}
