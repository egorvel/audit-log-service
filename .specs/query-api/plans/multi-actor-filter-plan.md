# Multi-actor filter — implementation plan

**Final location:** the plan should land at `.specs/query-api/plans/multi-actor-filter-plan.md`. Plan mode restricts edits to this `~/.claude/plans/...` path, so the first action after approval is `mkdir -p .specs/query-api/plans && mv` the file into place (or `cp`).

**Branch:** `query-api/multi-actor-filter` (already checked out; requirements / design / tasks updates already committed: `90e7f4a`, `db6934e`, `2311b5c`).

## Context

The Query API on `audit_events` shipped to master (T1–T7) with single-actor filtering. The new feature extends `GET /api/v1/audit-events` so `actor` accepts a comma-separated list of 1–10 distinct ids with set-membership semantics — a compliance officer can confirm/refute actions across a small cohort (user + service accounts) in one request; a security analyst can walk the union without N round trips.

Spec already pinned this session:
- **requirements.md** AC1.2 (reformulated to set-membership), AC1.11 (dedup), AC1.12 (400 on empty value/entry), AC1.13 (422 on >10 distinct), AC1.14 (400 on empty resource — symmetry flip), AC3.11 (cursor `fh` set-equal), AC3.12 (multi-actor walk, union, no dup/loss).
- **design.md** §1.2/§1.4/§3.3/§4/§5.2/§6/§8 updated; the multi-actor query rides the existing `idx_events_actor_ts_id` via Postgres `MergeAppend` over per-actor scans (no new index, justified in §4).
- **tasks.md** T8 (atomic code + IT) + T9 (OpenAPI), mirroring the T6/T7 split.

Two commits, in order: **T8** then **T9**, both on this branch.

---

## Commit 1 — T8: code + integration tests (atomic)

### Files modified

| File | Change |
| --- | --- |
| `src/main/java/com/sam/auditlog/service/EmptyFilterException.java` | **new** — extends `RuntimeException`; carries `List<String> errors()` parallel to `QueryValidationException` |
| `src/main/java/com/sam/auditlog/controller/GlobalExceptionHandler.java` | add `@ExceptionHandler(EmptyFilterException.class)` → 400 with envelope (mirror lines 49–52 for the 422 handler; reuse the envelope builder at lines 55–67) |
| `src/main/java/com/sam/auditlog/service/CursorCodec.java` | `filterHash` signature: `String actor` → `Set<String> actors` (param 1). Actor segment becomes `(actors == null \|\| actors.isEmpty()) ? "" : String.join("", actors.stream().sorted().toList())`. Rest of the four-segment hash unchanged. (`null` and empty-set hash identically — the empty-set branch is defensive; the service never passes empty.) |
| `src/main/java/com/sam/auditlog/service/QuerySpec.java` | `String actor` → `Set<String> actor` |
| `src/main/java/com/sam/auditlog/service/AuditEventQueryService.java` | **(a)** add public helpers `canonicalizeActor(List<String> raw) → Set<String>` and `requireNonBlankResource(String raw) → String`. Both throw `EmptyFilterException` on structural failure. `canonicalizeActor`: returns `null` if raw is `null` or empty list; else iterates entries, rejects any blank entry (collect all bad indices into one exception payload), returns `Set.copyOf` of the non-blank entries. **(b)** `validate(QuerySpec, int)` — drop the actor-blank check (lines 89–91) and the resource-blank check (lines 92–94); they now live in the structural helpers. Add a cap check: `spec.actor() != null && spec.actor().size() > 10` → `QueryValidationException("actor: at most 10 distinct ids per request, got N")`. **(c)** call-sites at lines 69 and 100 already pass `spec.actor()` to `cursorCodec.filterHash(...)` — the type change rides through unchanged |
| `src/main/java/com/sam/auditlog/repository/AuditEventRepository.java` | `findPage` param: `@Param("actor") String actor` → `@Param("actors") Collection<String> actors`. JPQL `(cast(:actor as text) is null or e.actorId = :actor)` → `(:actors is null or e.actorId in :actors)`. No `cast` needed — Hibernate types collections from the bound value. |
| `src/main/java/com/sam/auditlog/controller/AuditEventController.java` | `query(...)` signature: `@RequestParam(required=false) String actor` → `@RequestParam(required=false) List<String> actor`. Before building `QuerySpec`, call `queryService.canonicalizeActor(actor)` and `queryService.requireNonBlankResource(resource)`. Build `QuerySpec(canonicalActor, validatedResource, from, to, decodedCursor, limit)`. The cursor `decode` call (currently line 115) stays where it is. |

### Files NOT modified

- No DB migration (the multi-actor query rides the shipped `idx_events_actor_ts_id`).
- `pom.xml` — no new dependencies.
- `AuditEventConverter`, `AuditEvent` model, write-path controller code — out of scope.

### Validation order (enforced by code layout)

1. Cursor decode (controller calls `cursorCodec.decode`) → 400 on malformed.
2. Structural filter check (controller calls `canonicalizeActor` + `requireNonBlankResource`) → 400 on empty/blank.
3. `QuerySpec` constructed with canonical Set.
4. Range/limit/cap/cursor-version/cursor-fh checks (`AuditEventQueryService.validate`) → 422.

Dedup happens at step 2 (`Set.copyOf`), so the cap at step 4 always sees the distinct count.

### Tests

**Unit — modify `CursorCodecTest.java`:**
- Lines 85–93 `filterHash_isStableForEquivalentInputs`: update arg from `String` to `Set.of("u_42")`.
- Lines 96–105 `filterHash_differsWhenAnyFieldDiffers`: each "different actor" call now passes `Set.of(...)`.
- Lines 108–116 `filterHash_treatsNullAndEmptyConsistently`: rename → `filterHash_treatsNullAndEmptySetConsistently`; assert `null` and `Set.of()` hash identically.
- **Add** `filterHash_isSetEqualAcrossOrderAndMultiplicity`: assert `filterHash(Set.of("a","b"), ...)` == `filterHash(Set.of("b","a"), ...)`. (Set.of already dedups; the order-insensitivity comes from the internal `sorted()`.)
- **Add** `filterHash_differsByOneIdInSet`: `Set.of("a","b")` vs `Set.of("a","b","c")` hashes differ.

**Unit — modify `AuditEventQueryServiceTest.java`:**
- Lines 86–91 `query_blankActor_throws422` → **rewrite** as `canonicalizeActor_blankEntry_throws400` and similar; test new helpers directly. Original assertion site (calling `query(QuerySpec)` with `actor="   "`) no longer reachable because structural check is upstream.
- Lines 94–99 `query_blankResource_throws422` → **rewrite** as `requireNonBlankResource_blank_throws400`.
- **Add** `canonicalizeActor_dedup_returnsDistinctSet`: `["a","a","b"]` → `Set.of("a","b")`.
- **Add** `canonicalizeActor_capExceededAtSet_handledDownstreamIn422`: confirm `canonicalizeActor(["a1"…"a11"])` returns 11-element set without throwing (cap is service.validate's job, not the helper's).
- **Add** `query_actorSetOver10_throws422`: build a QuerySpec with 11-element Set → QueryValidationException carrying "at most 10" message.
- **Add** `query_cursorFhMatchesAcrossReorderedActorSet`: encode cursor with `Set.of("a","b")`, replay with `Set.of("b","a")` → no exception (sets compare equal post-canonicalization).
- **Add** `query_cursorFhMismatchOnDifferentActorSet`: encode with `Set.of("a","b")`, replay with `Set.of("a")` → QueryValidationException.

**Integration — extend `AuditEventQueryIntegrationTest.java`:**
- **AC1.2 reformulated** — new test `filterByMultipleActors_returnsUnion`: seed 3 actors `A/B/C` with 2 events each; query `?actor=A,B` → 4 events; assert id-set equals union of two single-actor queries.
- **AC1.11 dedup** — new test `actorListDedup_isInvariant`: `?actor=A,A,B` and `?actor=A,B` produce byte-identical response (events + next_cursor).
- **AC1.12 structural** — new test `actorEmptyOrEmptyEntry_returns400`: parameterized over `?actor=`, `?actor=A,,B`, `?actor=A,`, `?actor=,A` — all 400 with envelope; no event body.
- **AC1.13 cap** — new test `actorListCap`: 10 distinct → 200; 11 distinct → 422 with message naming the cap; 12 raw `A,A,B,B,C,D,E,F,G,H,I,J` (10 distinct after dedup) → 200.
- **AC1.14 resource symmetry** — new test `resourceEmpty_returns400`: `?resource=` → 400 with envelope. (No prior IT assertion to flip.)
- **AC3.11 cursor order-insensitivity** — new test `cursorReplayIsActorSetEqual`: mint cursor with `?actor=A,B`, replay page 2 with `?actor=B,A` → 200 and continues the walk; replay with `?actor=A,B,C` → 422; replay with `?actor=A` → 422.
- **AC3.12 multi-actor exhaustion** — new test `walkMultiActor_unionAndNoDup`: seed N events across `A/B/C`; walk `?actor=A,B,C&limit=3` via next_cursor; assert union of returned ids equals seeded set, `events.stream().map(id).distinct().count() == events.size()` per page and across all pages.

Reuse the existing `seedEvents(...)` test fixture helper (lines around 89 of the IT) — it already accepts an actor parameter; the new tests just call it three times for `A/B/C`.

### Compile / test gates

- `mvn -q -DskipTests=false test` — both unit tests and integration tests run; Testcontainers spins up Postgres.
- `LayerBoundaryTest` (ArchUnit) — unchanged; new exception lives in `service` package, handler stays in `controller`.

### Commit message (T8)

```
T8: Multi-actor filter for GET /api/v1/audit-events

actor parameter now accepts a 1–10-distinct-id comma-separated list
with set-membership semantics; cursor replay is order- and
multiplicity-insensitive on the actor set (AC3.11); present-but-empty
actor or resource values are reclassified from 422 to 400 via a new
EmptyFilterException (AC1.12/AC1.14).

- CursorCodec.filterHash signature: String actor → Set<String> actors;
  actor segment is sorted-deduped before hashing.
- QuerySpec.actor: String → Set<String>; controller calls
  AuditEventQueryService.canonicalizeActor for List→Set conversion
  with structural validation (AC1.11/AC1.12).
- AuditEventRepository.findPage: actor predicate becomes
  (:actors is null or e.actorId in :actors).
- AuditEventQueryService.validate: add 10-distinct-id cap (AC1.13),
  drop the blank checks now handled upstream.
- Integration tests extended to cover AC1.11–14 and AC3.11–12; two
  unit tests flipped from 422 to 400 expectations.

No DB migration; existing idx_events_actor_ts_id serves the IN-list
via MergeAppend over per-actor index scans (design §4).
```

---

## Commit 2 — T9: OpenAPI annotations

### Files modified

- `src/main/java/com/sam/auditlog/controller/AuditEventController.java` — on the `query` handler:
  - `@Parameter(name="actor", description="One or more distinct actor ids, comma-separated. Duplicates are silently dropped. Maximum 10 distinct ids per request. Empty value, empty entry, or trailing comma → 400. More than 10 distinct ids → 422.", example="u_42,svc_billing", array=@ArraySchema(schema=@Schema(type="string")))` — replaces the existing single-string `@Parameter`.
  - `@Parameter` on `resource`: update description to "Empty value → 400" (was 422 in T7).
  - `@ApiResponses` (or per-operation `@ApiResponse(responseCode="400", …)` and `@ApiResponse(responseCode="422", …)`): add the new bullets to the description body so `/v3/api-docs` matches design.md §1.4.

No `@Schema` changes on response components.

### Tests / verification

- Existing IT still green (T9 changes no behavior).
- `mvn spring-boot:run` → `curl http://localhost:8080/v3/api-docs | jq '.paths."/api/v1/audit-events".get.parameters'` shows `actor` as array-typed with the documented constraints.
- Visual check of Swagger UI at `/swagger-ui/index.html`.

### Commit message (T9)

```
T9: OpenAPI annotations for multi-actor actor parameter

Reflect AC1.2 (reformulated), AC1.11–14, and the 400/422 reshuffling
in the generated /v3/api-docs. No runtime behavior change.
```

---

## End-to-end verification (after both commits)

1. `mvn -q clean verify` — all unit + integration tests pass; ArchUnit clean.
2. `mvn -q spring-boot:run &` then smoke via curl (capture in PR description):
   - `curl 'http://localhost:8080/api/v1/audit-events?actor=u_1,svc_a&limit=2'` → 200 with paginated body.
   - `curl 'http://localhost:8080/api/v1/audit-events?actor='` → 400 envelope.
   - `curl 'http://localhost:8080/api/v1/audit-events?actor=a1,a2,a3,a4,a5,a6,a7,a8,a9,a10,a11'` → 422 naming the cap.
   - Mint a cursor with `?actor=u_1,svc_a`, replay with `?actor=svc_a,u_1` → 200; replay with `?actor=u_1` → 422.
3. `curl http://localhost:8080/v3/api-docs | jq '.paths."/api/v1/audit-events".get'` — `actor` schema is array, descriptions match design.md §1.4.

---

## Risks / fallbacks

- **Hibernate `:actors IS NULL` on a Collection parameter.** Primary path is `(:actors is null or e.actorId in :actors)` — works on Hibernate 6.4+ (which is the Spring Boot 3.3.5 dependency). **Fallback** if Hibernate misbehaves: split `findPage` into `findPageNoActor(...)` and `findPageWithActors(Collection<String>, ...)` and have `AuditEventQueryService.query` dispatch based on `spec.actor() == null`. Same coverage of the multi-actor IT scenarios; one extra repo method.
- **Spring's `List<String>` binding accepts repeated-param spelling (`?actor=a&actor=b`) too.** Documented form is comma-separated; the repeated-param form is a free side-effect that doesn't break any AC. Don't bother to reject it.
- **`@Parameter` on a `List<String>` query param.** Springdoc renders it as `style=form, explode=false` by default — matches `?actor=a,b,c`. If Swagger UI renders it as `explode=true` (repeated `?actor=a&actor=b`), add `explode = Explode.FALSE` to the annotation.
- **The 422→400 flip on `resource=""` is a behavior change to a shipped API.** It lands atomically with T8 (single commit). Existing IT has no assertion for this case (verified during exploration), so the only callers that would notice are external. Per requirements.md (AC1.14 with rationale) and AGENTS.md (back-compat waived for query API in initial work), this is acceptable.

## Critical files to read before starting

- `src/main/java/com/sam/auditlog/service/CursorCodec.java` (lines 85–107 — `filterHash` body and the U+001F separator handling)
- `src/main/java/com/sam/auditlog/service/AuditEventQueryService.java` (lines 81–108 — `validate` accumulator; lines 65–77 — has-more and next_cursor build)
- `src/main/java/com/sam/auditlog/controller/AuditEventController.java` (lines 91–118 — current `query` handler, especially the cursor decode at ~line 115)
- `src/main/java/com/sam/auditlog/repository/AuditEventRepository.java` (lines 24–43 — JPQL and the `cast(... as TEXT) IS NULL` pattern to mirror)
- `src/main/java/com/sam/auditlog/controller/GlobalExceptionHandler.java` (lines 19–67 — envelope builder + 400/422 mapping precedents)
- `src/test/java/com/sam/auditlog/service/CursorCodecTest.java` lines 85–125
- `src/test/java/com/sam/auditlog/service/AuditEventQueryServiceTest.java` lines 86–99 (the assertions to flip)
- `src/test/java/com/sam/auditlog/integration/AuditEventQueryIntegrationTest.java` lines 79–162 (filter section to extend) and lines 235–276 (cursor-mismatch section — model for new AC3.11 tests)
