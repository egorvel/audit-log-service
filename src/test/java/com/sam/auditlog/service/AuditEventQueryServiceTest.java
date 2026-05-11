package com.sam.auditlog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    @Test
    void query_blankActor_throws422() {
        QuerySpec spec = new QuerySpec("   ", null, null, null, null, 10);
        assertThatThrownBy(() -> service.query(spec))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("actor");
    }

    @Test
    void query_blankResource_throws422() {
        QuerySpec spec = new QuerySpec(null, "", null, null, null, 10);
        assertThatThrownBy(() -> service.query(spec))
                .isInstanceOf(QueryValidationException.class)
                .hasMessageContaining("resource");
    }

    @Test
    void query_cursorFhMismatch_throws422() {
        // Cursor was encoded with filter actor=alice; current request has actor=bob -> mismatch.
        Cursor encodedWithAlice =
                new Cursor(
                        CursorCodec.CURRENT_VERSION,
                        t0.plusSeconds(5),
                        "01HE3XJ7N2K9V0R1B6T8Q4WMZ9",
                        codec.filterHash("alice", null, null, null));
        QuerySpec spec = new QuerySpec("bob", null, null, null, encodedWithAlice, 10);

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

        AuditEventPage page = service.query(new QuerySpec("a", null, null, null, null, limit));

        assertThat(page.events()).hasSize(limit);
        assertThat(page.nextCursor()).isNotNull();

        Cursor decoded = codec.decode(page.nextCursor());
        AuditEvent boundary = rows.get(limit - 1);
        assertThat(decoded.ts()).isEqualTo(boundary.timestamp());
        assertThat(decoded.id()).isEqualTo(boundary.id());
        assertThat(decoded.fh()).isEqualTo(codec.filterHash("a", null, null, null));
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
