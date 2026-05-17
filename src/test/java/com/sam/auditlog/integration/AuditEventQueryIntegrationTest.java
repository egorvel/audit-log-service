package com.sam.auditlog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import com.sam.auditlog.service.Cursor;
import com.sam.auditlog.service.CursorCodec;

/**
 * End-to-end test for {@code GET /api/v1/audit-events}. The events table is append-only
 * (UPDATE/DELETE/TRUNCATE blocked), so isolation between tests is achieved by allocating each test
 * a disjoint tag-derived time window and scoping queries to it via {@code from}/{@code to}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuditEventQueryIntegrationTest {

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

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired CursorCodec codec;

    private static final Instant ANCHOR = Instant.parse("2026-04-01T00:00:00Z");

    private static Instant windowStart(String tag) {
        long slot = Math.floorMod((long) tag.hashCode(), 1_000_000L);
        return ANCHOR.plusSeconds(slot * 3600L);
    }

    // ---------- Filters (AC1.1-AC1.6, AC1.10) ----------

    @Test
    void filterByActor_returnsOnlyMatching() throws Exception {
        String tag = "filterByActor";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(600);
        seed(from, 3, tag + "_A", "res");
        seed(from.plusSeconds(10), 2, tag + "_B", "res");

        JsonNode body =
                getOk(
                        "/api/v1/audit-events?actor="
                                + tag
                                + "_A&from="
                                + from
                                + "&to="
                                + to
                                + "&limit=50");

        assertThat(body.get("events")).hasSize(3);
        body.get("events")
                .forEach(e -> assertThat(e.get("actor").get("id").asText()).isEqualTo(tag + "_A"));
    }

    @Test
    void filterByResource_returnsOnlyMatching() throws Exception {
        String tag = "filterByResource";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(600);
        seed(from, 2, tag, tag + "_X");
        seed(from.plusSeconds(10), 4, tag, tag + "_Y");

        JsonNode body =
                getOk("/api/v1/audit-events?resource=" + tag + "_X&from=" + from + "&to=" + to);

        assertThat(body.get("events")).hasSize(2);
        body.get("events")
                .forEach(
                        e ->
                                assertThat(e.get("resource").get("id").asText())
                                        .isEqualTo(tag + "_X"));
    }

    @Test
    void filterByTimeRangeIsHalfOpen() throws Exception {
        String tag = "halfOpen";
        Instant base = windowStart(tag);
        insertAt(base.plusSeconds(1), tag, tag);
        insertAt(base.plusSeconds(2), tag, tag);
        insertAt(base.plusSeconds(3), tag, tag);

        JsonNode body =
                getOk(
                        "/api/v1/audit-events?from="
                                + base.plusSeconds(2)
                                + "&to="
                                + base.plusSeconds(3));

        assertThat(body.get("events")).hasSize(1);
        assertThat(body.get("events").get(0).get("timestamp").asText())
                .startsWith(base.plusSeconds(2).toString().substring(0, 19));
    }

    @Test
    void filterByActorAndResource_combined() throws Exception {
        String tag = "combo";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(600);
        seed(from, 2, tag + "_A", tag + "_X");
        seed(from.plusSeconds(20), 2, tag + "_A", tag + "_Y");
        seed(from.plusSeconds(40), 2, tag + "_B", tag + "_X");

        JsonNode body =
                getOk(
                        "/api/v1/audit-events?actor="
                                + tag
                                + "_A&resource="
                                + tag
                                + "_X&from="
                                + from
                                + "&to="
                                + to);

        assertThat(body.get("events")).hasSize(2);
    }

    // ---------- Response shape (AC1.7, AC2.4) ----------

    @Test
    void responseShape_includesAllDocumentedFieldsAndRfc3339Z() throws Exception {
        String tag = "shape";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(600);
        insertAt(from.plusSeconds(1), tag, tag);

        JsonNode body = getOk("/api/v1/audit-events?actor=" + tag + "&from=" + from + "&to=" + to);

        JsonNode e = body.get("events").get(0);
        assertThat(e.get("id").asText()).hasSize(26);
        assertThat(e.get("timestamp").asText()).endsWith("Z");
        assertThat(e.get("actor").get("id").asText()).isEqualTo(tag);
        assertThat(e.get("actor").get("type").asText()).isEqualTo("unknown");
        assertThat(e.get("resource").get("id").asText()).isEqualTo(tag);
        assertThat(e.get("resource").get("type").asText()).isEqualTo("unknown");
        assertThat(e.get("action").asText()).isEqualTo("act");
        assertThat(e.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(e.get("context")).isNotNull();
    }

    // ---------- Parse errors (AC1.8, AC3.8a, AC3.9a) ----------

    @Test
    void malformedFrom_returns400() throws Exception {
        mvc.perform(get("/api/v1/audit-events?from=not-a-date")).andExpect(status().isBadRequest());
    }

    @Test
    void malformedLimit_returns400() throws Exception {
        mvc.perform(get("/api/v1/audit-events?limit=NaN")).andExpect(status().isBadRequest());
    }

    @Test
    void malformedCursor_notBase64_returns400() throws Exception {
        mvc.perform(get("/api/v1/audit-events?cursor=%21%21%21not-base64%21%21%21"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void malformedCursor_validBase64ButNotJson_returns400() throws Exception {
        String token =
                Base64.getUrlEncoder().withoutPadding().encodeToString("not json".getBytes());
        mvc.perform(get("/api/v1/audit-events?cursor=" + token)).andExpect(status().isBadRequest());
    }

    // ---------- Semantic errors (AC1.9, AC3.5, AC3.8b, AC3.9b) ----------

    @Test
    void fromAfterTo_returns422() throws Exception {
        Instant from = ANCHOR;
        mvc.perform(get("/api/v1/audit-events?from=" + from.plusSeconds(60) + "&to=" + from))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void limitTooSmall_returns422() throws Exception {
        mvc.perform(get("/api/v1/audit-events?limit=0"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void limitTooLarge_returns422() throws Exception {
        mvc.perform(get("/api/v1/audit-events?limit=201"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void cursorEncodedWithFilter_replayedWithoutFilter_returns422() throws Exception {
        Cursor cursor =
                new Cursor(
                        CursorCodec.CURRENT_VERSION,
                        ANCHOR,
                        UlidCreator.getMonotonicUlid().toString(),
                        codec.filterHash(Set.of("alice"), null, null, null));
        String token = codec.encode(cursor);

        // Replay with no actor filter -> filter set differs -> 422.
        mvc.perform(get("/api/v1/audit-events?cursor=" + token))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void cursorEncodedWithoutFilter_replayedWithFilter_returns422() throws Exception {
        Cursor cursor =
                new Cursor(
                        CursorCodec.CURRENT_VERSION,
                        ANCHOR,
                        UlidCreator.getMonotonicUlid().toString(),
                        codec.filterHash(null, null, null, null));
        String token = codec.encode(cursor);

        mvc.perform(get("/api/v1/audit-events?actor=bob&cursor=" + token))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void cursorWithBumpedVersion_returns422() throws Exception {
        Cursor cursor =
                new Cursor(
                        CursorCodec.CURRENT_VERSION + 1,
                        ANCHOR,
                        UlidCreator.getMonotonicUlid().toString(),
                        codec.filterHash(null, null, null, null));
        String token = codec.encode(cursor);

        mvc.perform(get("/api/v1/audit-events?cursor=" + token))
                .andExpect(status().isUnprocessableEntity());
    }

    // ---------- Ordering & ties (AC2.1-AC2.3) ----------

    @Test
    void sameMillisecondTimestamps_orderedByUlidDesc() throws Exception {
        String tag = "ties";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(600);
        Instant ts = from.plusSeconds(60);
        String idLow = "01HE3XJ7N2K9V0R1B6T8Q4WMZ0";
        String idHigh = "01HE3XJ7N2K9V0R1B6T8Q4WMZ9";
        insertWithId(idLow, ts, tag, tag);
        insertWithId(idHigh, ts, tag, tag);

        JsonNode body = getOk("/api/v1/audit-events?actor=" + tag + "&from=" + from + "&to=" + to);

        assertThat(body.get("events")).hasSize(2);
        assertThat(body.get("events").get(0).get("id").asText().trim()).isEqualTo(idHigh);
        assertThat(body.get("events").get(1).get("id").asText().trim()).isEqualTo(idLow);
    }

    // ---------- Pagination & stability (AC3.1, AC3.3, AC3.4, AC3.6, AC3.10) ----------

    @Test
    void walkAllPages_unionEqualsSeedSet_noDuplicates() throws Exception {
        String tag = "walk";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(3600);
        int total = 7;
        seedSequential(from, total, tag, tag);

        Set<String> seen = new HashSet<>();
        String nextCursor = null;
        int pageSize = 3;
        int pages = 0;

        while (true) {
            String url =
                    "/api/v1/audit-events?actor="
                            + tag
                            + "&from="
                            + from
                            + "&to="
                            + to
                            + "&limit="
                            + pageSize
                            + (nextCursor == null ? "" : "&cursor=" + nextCursor);
            JsonNode page = getOk(url);
            pages++;
            for (JsonNode e : page.get("events")) {
                String id = e.get("id").asText().trim();
                assertThat(seen.add(id)).as("no duplicate id %s", id).isTrue();
            }
            if (page.get("next_cursor") == null || page.get("next_cursor").isNull()) {
                break;
            }
            nextCursor = page.get("next_cursor").asText();
            assertThat(pages).isLessThan(10).as("paging should terminate");
        }
        assertThat(seen).hasSize(total);
    }

    @Test
    void cursorIsStableAgainstLaterInserts() throws Exception {
        String tag = "stable";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(3600);
        seedSequential(from, 4, tag, tag);

        JsonNode firstPage =
                getOk(
                        "/api/v1/audit-events?actor="
                                + tag
                                + "&from="
                                + from
                                + "&to="
                                + to
                                + "&limit=2");
        String nextCursor = firstPage.get("next_cursor").asText();

        // Insert newer rows; the cursor pins us to the original snapshot's (timestamp, id) window
        // so these newer rows must NOT appear in page 2.
        Instant newer = from.plusSeconds(1000);
        seedSequential(newer, 3, tag, tag);

        JsonNode secondPage =
                getOk(
                        "/api/v1/audit-events?actor="
                                + tag
                                + "&from="
                                + from
                                + "&to="
                                + to
                                + "&limit=2&cursor="
                                + nextCursor);

        // Page 2 has the remaining 2 rows from the original snapshot, not the new ones.
        assertThat(secondPage.get("events")).hasSize(2);
        secondPage
                .get("events")
                .forEach(
                        e -> {
                            Instant ts = Instant.parse(e.get("timestamp").asText());
                            assertThat(ts).isBefore(newer);
                        });
    }

    // ---------- Limit edge (AC3.7) ----------

    @Test
    void defaultLimitIsFifty_andFinalPageOmitsNextCursor() throws Exception {
        String tag = "default";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(3600);
        seedSequential(from, 3, tag, tag);

        JsonNode body = getOk("/api/v1/audit-events?actor=" + tag + "&from=" + from + "&to=" + to);

        assertThat(body.get("events")).hasSize(3);
        assertThat(body.has("next_cursor")).isFalse();
    }

    @Test
    void explicitLimit200_isAccepted() throws Exception {
        String tag = "max";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(3600);

        mvc.perform(
                        get(
                                "/api/v1/audit-events?actor="
                                        + tag
                                        + "&from="
                                        + from
                                        + "&to="
                                        + to
                                        + "&limit=200"))
                .andExpect(status().isOk());
    }

    @Test
    void finalPageEqualsLimit_omitsNextCursor() throws Exception {
        String tag = "exact";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(3600);
        seedSequential(from, 3, tag, tag);

        JsonNode body =
                getOk(
                        "/api/v1/audit-events?actor="
                                + tag
                                + "&from="
                                + from
                                + "&to="
                                + to
                                + "&limit=3");
        assertThat(body.get("events")).hasSize(3);
        assertThat(body.has("next_cursor")).isFalse();
    }

    // ---------- Multi-actor filter (AC1.2 reformulated, AC1.11-14, AC3.11, AC3.12) ----------

    @Test
    void filterByMultipleActors_returnsUnion() throws Exception {
        String tag = "multiActor";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(600);
        seed(from, 2, tag + "_A", "res");
        seed(from.plusSeconds(10), 2, tag + "_B", "res");
        seed(from.plusSeconds(20), 2, tag + "_C", "res");

        JsonNode multi =
                getOk(
                        "/api/v1/audit-events?actor="
                                + tag
                                + "_A,"
                                + tag
                                + "_B&from="
                                + from
                                + "&to="
                                + to
                                + "&limit=50");
        JsonNode onlyA =
                getOk(
                        "/api/v1/audit-events?actor="
                                + tag
                                + "_A&from="
                                + from
                                + "&to="
                                + to
                                + "&limit=50");
        JsonNode onlyB =
                getOk(
                        "/api/v1/audit-events?actor="
                                + tag
                                + "_B&from="
                                + from
                                + "&to="
                                + to
                                + "&limit=50");

        Set<String> union = new HashSet<>(ids(onlyA.get("events")));
        union.addAll(ids(onlyB.get("events")));
        Set<String> got = new HashSet<>(ids(multi.get("events")));

        assertThat(multi.get("events")).hasSize(4);
        assertThat(got).isEqualTo(union);
    }

    @Test
    void actorListDedup_isInvariant() throws Exception {
        String tag = "dedup";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(600);
        seed(from, 2, tag + "_A", "res");
        seed(from.plusSeconds(10), 2, tag + "_B", "res");

        JsonNode withDupes =
                getOk(
                        "/api/v1/audit-events?actor="
                                + tag
                                + "_A,"
                                + tag
                                + "_A,"
                                + tag
                                + "_B&from="
                                + from
                                + "&to="
                                + to
                                + "&limit=50");
        JsonNode canonical =
                getOk(
                        "/api/v1/audit-events?actor="
                                + tag
                                + "_A,"
                                + tag
                                + "_B&from="
                                + from
                                + "&to="
                                + to
                                + "&limit=50");

        // AC1.11: dedup is silent and total - the event list and next_cursor are identical.
        assertThat(ids(withDupes.get("events"))).isEqualTo(ids(canonical.get("events")));
        assertThat(withDupes.get("next_cursor")).isEqualTo(canonical.get("next_cursor"));
    }

    @Test
    void actorEmptyOrEmptyEntry_returns400() throws Exception {
        // AC1.12: structural failures - present-but-empty or any blank entry -> 400.
        for (String raw : List.of("", "A,,B", "A,", ",A")) {
            mvc.perform(get("/api/v1/audit-events?actor=" + raw))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Empty filter value"));
        }
    }

    @Test
    void actorListCap_tenDistinctOk_elevenDistinct422_twelveRawTenDistinctOk() throws Exception {
        // AC1.13: cap is on distinct count after dedup, not on raw entry count.
        mvc.perform(get("/api/v1/audit-events?actor=a1,a2,a3,a4,a5,a6,a7,a8,a9,a10"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/audit-events?actor=a1,a2,a3,a4,a5,a6,a7,a8,a9,a10,a11"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("at most 10")));

        // 12 raw entries but only 10 distinct after dedup -> accepted.
        mvc.perform(get("/api/v1/audit-events?actor=A,A,B,B,C,D,E,F,G,H,I,J"))
                .andExpect(status().isOk());
    }

    @Test
    void resourceEmpty_returns400() throws Exception {
        // AC1.14: present-but-blank resource is a structural failure -> 400, not 422.
        mvc.perform(get("/api/v1/audit-events?resource="))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Empty filter value"));
    }

    @Test
    void cursorReplayIsActorSetEqual() throws Exception {
        // AC3.11: cursor minted under {A,B} accepted when replayed with {A,B} or {B,A},
        // rejected when replayed with any different set.
        String mintedFh = codec.filterHash(Set.of("A", "B"), null, null, null);
        Cursor minted =
                new Cursor(
                        CursorCodec.CURRENT_VERSION,
                        ANCHOR,
                        UlidCreator.getMonotonicUlid().toString(),
                        mintedFh);
        String token = codec.encode(minted);

        mvc.perform(get("/api/v1/audit-events?actor=A,B&cursor=" + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/audit-events?actor=B,A&cursor=" + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/audit-events?actor=A,B,C&cursor=" + token))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/audit-events?actor=A&cursor=" + token))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void walkMultiActor_unionAndNoDup() throws Exception {
        String tag = "walkMulti";
        Instant from = windowStart(tag);
        Instant to = from.plusSeconds(3600);
        int perActor = 4;
        seedSequential(from, perActor, tag + "_A", "res");
        seedSequential(from.plusSeconds(100), perActor, tag + "_B", "res");
        seedSequential(from.plusSeconds(200), perActor, tag + "_C", "res");

        Set<String> seen = new HashSet<>();
        String nextCursor = null;
        int pages = 0;
        while (true) {
            String url =
                    "/api/v1/audit-events?actor="
                            + tag
                            + "_A,"
                            + tag
                            + "_B,"
                            + tag
                            + "_C&from="
                            + from
                            + "&to="
                            + to
                            + "&limit=3"
                            + (nextCursor == null ? "" : "&cursor=" + nextCursor);
            JsonNode page = getOk(url);
            pages++;
            for (JsonNode e : page.get("events")) {
                String id = e.get("id").asText().trim();
                assertThat(seen.add(id)).as("no duplicate id %s across pages", id).isTrue();
            }
            if (page.get("next_cursor") == null || page.get("next_cursor").isNull()) {
                break;
            }
            nextCursor = page.get("next_cursor").asText();
            assertThat(pages).isLessThan(10).as("paging should terminate");
        }
        // AC3.12: union over all pages equals the seeded set; no dupes, no losses.
        assertThat(seen).hasSize(3 * perActor);
    }

    // ---------- helpers ----------

    private JsonNode getOk(String url) throws Exception {
        MvcResult result = mvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private void seedSequential(Instant from, int count, String actor, String resource) {
        for (int i = 0; i < count; i++) {
            insertAt(from.plusSeconds(i), actor, resource);
        }
    }

    private void seed(Instant from, int count, String actor, String resource) {
        seedSequential(from, count, actor, resource);
    }

    private void insertAt(Instant ts, String actor, String resource) {
        insertWithId(UlidCreator.getMonotonicUlid().toString(), ts, actor, resource);
    }

    private void insertWithId(String id, Instant ts, String actor, String resource) {
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

    private static List<String> ids(JsonNode events) {
        List<String> ids = new ArrayList<>();
        events.forEach(e -> ids.add(e.get("id").asText().trim()));
        return ids;
    }
}
