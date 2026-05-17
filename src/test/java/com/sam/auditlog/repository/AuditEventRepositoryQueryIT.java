package com.sam.auditlog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.f4b6a3.ulid.UlidCreator;
import com.sam.auditlog.model.AuditEvent;

/**
 * Integration test for the keyset-paginated repository method. The events table is append-only
 * (UPDATE/DELETE/TRUNCATE blocked by triggers), so test isolation is achieved by allocating each
 * test a disjoint time window derived from its tag, then scoping every query to that window via
 * {@code from}/{@code to}. Rows are inserted directly via JdbcTemplate so the test controls the
 * timestamp on each row (the runtime path defers to the DB DEFAULT).
 */
@SpringBootTest
@Testcontainers
class AuditEventRepositoryQueryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:18.3-alpine")
                    .withDatabaseName("auditlog")
                    .withUsername("test")
                    .withPassword("test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void overrideProps(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        reg.add("spring.datasource.username", POSTGRES::getUsername);
        reg.add("spring.datasource.password", POSTGRES::getPassword);
        reg.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        reg.add("spring.flyway.user", POSTGRES::getUsername);
        reg.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired AuditEventRepository repository;
    @Autowired JdbcTemplate jdbc;

    /** Common anchor; each test offsets from it by a tag-derived amount. */
    private static final Instant ANCHOR = Instant.parse("2026-04-01T00:00:00Z");

    /** Per-test window is {@value} seconds wide; well above any test's row count. */
    private static final long WINDOW_SECONDS = 600;

    @Test
    void findPage_noFilters_returnsAllInDescOrder() {
        String tag = "noFilters";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(WINDOW_SECONDS);
        seedSequential(tag, 5, from);

        List<AuditEvent> page =
                repository.findPage(null, null, from, to, null, null, PageRequest.of(0, 10));

        assertThat(page).hasSize(5);
        assertOrderedDesc(page);
    }

    @Test
    void findPage_filterByActor_returnsOnlyMatching() {
        String tag = "byActor";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(WINDOW_SECONDS);
        seedSequential("actor_A", 3, from);
        seedSequential("actor_B", 2, from.plusSeconds(10));

        List<AuditEvent> page =
                repository.findPage(
                        List.of("actor_A"), null, from, to, null, null, PageRequest.of(0, 10));

        assertThat(page).hasSize(3);
        assertThat(page).allMatch(e -> "actor_A".equals(e.actorId()));
    }

    @Test
    void findPage_filterByResource_returnsOnlyMatching() {
        String tag = "byResource";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(WINDOW_SECONDS);
        seedSequential(tag, 2, from, "actor_R", "res_X");
        seedSequential(tag, 4, from.plusSeconds(10), "actor_R", "res_Y");

        List<AuditEvent> page =
                repository.findPage(null, "res_X", from, to, null, null, PageRequest.of(0, 10));

        assertThat(page).hasSize(2);
        assertThat(page).allMatch(e -> "res_X".equals(e.resourceId()));
    }

    @Test
    void findPage_timeRangeIsHalfOpen() {
        // from is inclusive, to is exclusive (AC1.4 / AC1.5).
        String tag = "halfOpen";
        Instant base = windowStart(tag);
        insertAt(base.plusSeconds(1), tag, tag);
        insertAt(base.plusSeconds(2), tag, tag);
        insertAt(base.plusSeconds(3), tag, tag);

        List<AuditEvent> page =
                repository.findPage(
                        null,
                        null,
                        base.plusSeconds(2),
                        base.plusSeconds(3),
                        null,
                        null,
                        PageRequest.of(0, 10));

        // Only the +2s row matches: +1s is below `from`, +3s is excluded by half-open `to`.
        assertThat(page).hasSize(1);
        assertThat(page.get(0).timestamp()).isEqualTo(base.plusSeconds(2));
    }

    @Test
    void findPage_sameMillisecondTiebreakerByIdDesc() {
        // Two rows at the same exact millisecond -- order must fall back to id DESC.
        String tag = "tiebreaker";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(WINDOW_SECONDS);
        Instant ts = from.plusSeconds(60);
        String idLow = "01HE3XJ7N2K9V0R1B6T8Q4WMZ0";
        String idHigh = "01HE3XJ7N2K9V0R1B6T8Q4WMZ9";
        insertWithExplicitId(idLow, ts, "tieActor", "tieResource");
        insertWithExplicitId(idHigh, ts, "tieActor", "tieResource");

        List<AuditEvent> page =
                repository.findPage(
                        List.of("tieActor"), null, from, to, null, null, PageRequest.of(0, 10));

        assertThat(page).hasSize(2);
        assertThat(page.get(0).id().trim()).isEqualTo(idHigh);
        assertThat(page.get(1).id().trim()).isEqualTo(idLow);
    }

    @Test
    void findPage_keysetCursor_returnsRowsStrictlyAfterPosition() {
        String tag = "keyset";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(WINDOW_SECONDS);
        seedSequential(tag, 6, from);

        List<AuditEvent> firstPage =
                repository.findPage(
                        List.of(tag), null, from, to, null, null, PageRequest.of(0, 3));
        assertThat(firstPage).hasSize(3);

        AuditEvent last = firstPage.get(firstPage.size() - 1);
        List<AuditEvent> secondPage =
                repository.findPage(
                        List.of(tag),
                        null,
                        from,
                        to,
                        last.timestamp(),
                        last.id(),
                        PageRequest.of(0, 3));

        assertThat(secondPage).hasSize(3);
        assertThat(secondPage.get(0).timestamp()).isBeforeOrEqualTo(last.timestamp());
        // No overlap between pages.
        assertThat(secondPage)
                .extracting(AuditEvent::id)
                .doesNotContainAnyElementsOf(firstPage.stream().map(AuditEvent::id).toList());
    }

    @Test
    void findPage_limitPlusOnePattern_drivesHasMoreDetection() {
        String tag = "limitPlus";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(WINDOW_SECONDS);
        seedSequential(tag, 5, from);

        // Caller asks for limit+1 = 4. If exactly 4 came back, more rows exist.
        List<AuditEvent> rows =
                repository.findPage(
                        List.of(tag), null, from, to, null, null, PageRequest.of(0, 4));

        assertThat(rows).hasSize(4);
    }

    // ---------- helpers ----------

    /**
     * Allocates a tag-deterministic, disjoint time window so tests do not see each other's rows.
     * Windows are placed 1 hour apart, derived from the tag's hash, all relative to {@link
     * #ANCHOR}.
     */
    private static Instant windowStart(String tag) {
        long slot = Math.floorMod((long) tag.hashCode(), 1_000_000L);
        return ANCHOR.plusSeconds(slot * 3600L);
    }

    private void seedSequential(String actor, int count, Instant from) {
        seedSequential(actor, count, from, actor, actor);
    }

    private void seedSequential(
            String tag, int count, Instant from, String actor, String resource) {
        for (int i = 0; i < count; i++) {
            insertWithExplicitId(
                    UlidCreator.getMonotonicUlid().toString(),
                    from.plusSeconds(i),
                    actor,
                    resource);
        }
    }

    private void insertAt(Instant ts, String actor, String resource) {
        insertWithExplicitId(UlidCreator.getMonotonicUlid().toString(), ts, actor, resource);
    }

    private void insertWithExplicitId(String id, Instant ts, String actor, String resource) {
        jdbc.update(
                "INSERT INTO audit_events"
                        + " (id, timestamp, actor_id, actor_type, resource_id, resource_type,"
                        + " action, outcome, context) VALUES (?, ?, ?, 'unknown', ?, 'unknown',"
                        + " 'act', 'success', '{}'::jsonb)",
                id,
                java.sql.Timestamp.from(ts),
                actor,
                resource);
    }

    private static void assertOrderedDesc(List<AuditEvent> page) {
        for (int i = 1; i < page.size(); i++) {
            AuditEvent prev = page.get(i - 1);
            AuditEvent curr = page.get(i);
            // (timestamp, id) DESC means: prev > curr lexicographically.
            assertThat(
                            prev.timestamp().isAfter(curr.timestamp())
                                    || (prev.timestamp().equals(curr.timestamp())
                                            && prev.id().compareTo(curr.id()) > 0))
                    .as(
                            "row %d (%s, %s) should sort after row %d (%s, %s)",
                            i - 1, prev.timestamp(), prev.id(), i, curr.timestamp(), curr.id())
                    .isTrue();
        }
    }
}
