# Query API on `audit_events` — Tasks

Each task is one safe commit: the tree compiles, all tests pass, and the
runtime state (DB schema ↔ JPA model) is consistent at the end of the task.
References use §-style markers from `requirements.md` (AC…) and `design.md` (§…).

## Dependency graph

```
T1 ── T2 ── T4 ── T5 ── T6 ── T7 ── T8 ── T9
        │         │
        └── T3 ───┘
```

- **T1** UlidFactory + dependency
- **T2** V2 migration + entity/DTO/converter + write-path move (atomic)
- **T3** Cursor codec + new exceptions + handler bindings
- **T4** Repository keyset query method
- **T5** `AuditEventQueryService` (orchestration + semantic validation)
- **T6** `GET /api/v1/audit-events` controller wiring + end-to-end coverage
- **T7** OpenAPI / springdoc annotations
- **T8** Multi-actor filter: extend read path to a 1–10-id actor set (atomic across codec, service, repo, controller, exceptions, IT)
- **T9** OpenAPI updates for the multi-actor parameter

T2 and T3 are independent and can land in either order; everything from T4
onward depends on T2; T5 depends on both T3 and T4. T8 builds on the shipped
T1–T7 baseline (single-actor) and lands the multi-actor extension in one
commit (signature changes to `CursorCodec.filterHash`, `QuerySpec.actor`, the
repository `findPage` parameter, and the controller binding ripple together,
so splitting them would produce intermediate commits that don't compile). T9
follows T8 the same way T7 followed T6.

---

## T1 — Introduce `UlidFactory` and the ULID library

**Goal.** Add a single, monotonic-per-JVM ULID generator usable by the write
path before any other code starts producing ULIDs.

**Refs.** design §2.2 (ULID generation), §6.2 (`service.UlidFactory`).

**Scope.**
- Add `com.github.f4b6a3:ulid-creator:5.2.3` to `pom.xml`.
- Create `service.UlidFactory` as a `@Component` wrapping a single
  monotonic factory instance; expose `String next()` returning the
  Crockford-Base32 26-char form.
- Unit test: `UlidFactoryTest` — generated values are 26 chars, lex-sortable,
  monotonic when produced inside the same millisecond, and parse cleanly back.

**DoD.**
- `UlidFactory` is a Spring bean and is injectable.
- `UlidFactoryTest` passes (`mvn -pl . test -Dtest=UlidFactoryTest`).
- Full `mvn verify` is green; no existing test broke.
- No call sites use `UlidFactory` yet (it's wired in T2).

**Out of scope.** Any change to `AuditEvent`, controller, or schema.

---

## T2 — V2 migration, model change, and write-path move (atomic)

**Goal.** Move the system from the V1 schema/contract to the V2 schema/contract
in one commit, since DB and JPA mapping must stay in sync.

**Refs.**
- requirements §Context (model change), AC1.7 (response shape), AC2.4 (preserve
  timestamp).
- design §2 (data model), §2.3 (migration), §2.4 (write-path body change),
  §6.1 (DTOs / controller), §6.2 (entity/converter), §6.3 (migration file).

**Scope.**

*Migration* — `src/main/java/com/sam/auditlog/db/migration/V2__query_api_model.java`
(Flyway `BaseJavaMigration`, so it can instantiate `UlidFactory` and call
`fromTimestamp(timestamp)` per row instead of re-implementing ULID generation
in PL/pgSQL — see `_delta.md` v2→v3) implementing design §2.3:
1. `CREATE TABLE audit_events_new (...)` per design §2.1.
2. `INSERT INTO audit_events_new SELECT ...` from old table, deriving
   `id` (fresh ULID per row, seeded from row's `timestamp` so ordering is
   preserved), `actor_id` ← old `actor`, `actor_type` ← `'unknown'`,
   `resource_id` ← old `resource`, `resource_type` ← `'unknown'`.
3. `DROP TABLE audit_events`.
4. `ALTER TABLE audit_events_new RENAME TO audit_events`.
5. Re-create `audit_events_no_update`, `audit_events_no_delete`,
   `audit_events_no_truncate` triggers.
6. `GRANT SELECT, INSERT ON audit_events TO audit_app`.
7. Create indexes from design §4: `idx_events_ts_id`,
   `idx_events_actor_ts_id`, `idx_events_res_ts_id`. Old V1 standalone
   `idx_audit_events_action` / `idx_audit_events_actor` are gone implicitly
   with the dropped table.

*Java*
- `model.AuditEvent`: `Long id` → `String id` (assigned via `UlidFactory` at
  construction); flat `actor`/`resource` strings → `actorId`, `actorType`,
  `resourceId`, `resourceType`. Tighten constructor's `Objects.requireNonNull`
  to cover all four. Keep `@Generated(INSERT)` on `timestamp`. Stay immutable.
- `dto.ActorRef`, `dto.ResourceRef` — records `(String id, String type)`,
  both fields `@NotBlank`.
- `dto.AuditEventResponse` — updated to design §6.1 shape (id/timestamp/actor/
  resource/action/outcome/context).
- `dto.CreateAuditEventRequest` — `actor`/`resource` become `ActorRef`/
  `ResourceRef` with `@Valid` cascade.
- `converter.AuditEventConverter` — `toEntity(...)` injects `id` from
  `UlidFactory`; `toResponse(...)` builds nested DTO.
- `controller.AuditEventController` — `@RequestMapping("/api/v1/events")`
  becomes `@RequestMapping("/api/v1/audit-events")`. Existing `POST` handler
  stays in place but now consumes the new request body shape. The old `GET`
  (whatever currently uses `findAllByOrderByIdDesc`) is removed; the
  replacement `GET` lands in T6.
- `repository.AuditEventRepository` — drop `findAllByOrderByIdDesc` (no
  longer compiles against `String id` and is replaced in T4). Keep
  `JpaRepository<AuditEvent, String>`.

**DoD.**
- `mvn verify` green: V2 migration applies cleanly on top of V1 in
  Testcontainers, including the trigger and grant re-installation.
- Migration regression test (new) — boots a Postgres container with V1
  fixture rows, applies V2, asserts: row count preserved; every row has a
  26-char ULID `id`; ordering by `id ASC` matches the old `timestamp ASC`
  ordering for those rows; `actor_type` = `resource_type` = `'unknown'` on
  historical rows; an attempted `UPDATE`/`DELETE`/`TRUNCATE` is rejected by
  triggers; the three new indexes exist.
- Existing write-path integration test (`POST /api/v1/audit-events`) passes
  with the new body shape and stores rows with all six structured fields.
- `LayerBoundaryTest` and other ArchUnit rules still pass without changes.

**Out of scope.** Cursor, query endpoint, query service, OpenAPI text changes.

---

## T3 — `CursorCodec`, validation exceptions, handler bindings

**Goal.** Land all cursor / validation primitives so T5 can compose them.

**Refs.** design §1.4 (error mapping), §3.3 (cursor format + filter hash),
§5 (validation), §6.1 (handler), §6.2 (`CursorCodec`); AC3.2, AC3.4,
AC3.5, AC3.9a, AC3.9b.

**Scope.**
- `service.CursorDecodeException extends IllegalArgumentException` — thrown
  on base64/JSON decode failure of a cursor.
- `service.QueryValidationException extends RuntimeException` — thrown on any
  semantic rejection (range, limit, empty filter, unknown cursor `v`,
  filter-hash mismatch). Carries field-level messages compatible with the
  existing `errors: [...]` envelope.
- `service.Cursor` — value record `(int v, Instant ts, String id, String fh)`.
- `service.CursorCodec` — `String encode(Cursor)`, `Cursor decode(String)`,
  `String filterHash(String actor, String resource, Instant from, Instant to)`
  (nullable args; matches the four-value sha256 input in design §3.3, so the
  codec stays free of any service-layer type — `QuerySpec` is created in T5).
  No Spring deps, no I/O. Uses `Base64.getUrlDecoder/Encoder` and a single
  shared `ObjectMapper`. Decode failures → `CursorDecodeException`; unknown
  `v` and (later) hash mismatch → `QueryValidationException`.
- `controller.GlobalExceptionHandler` — add two `@ExceptionHandler`s:
  `CursorDecodeException` → 400 with the existing envelope shape;
  `QueryValidationException` → 422 with the existing envelope shape.

*Tests (unit, no Spring context):*
- `CursorCodecTest` — round-trip encode/decode preserves all four fields;
  malformed base64 → `CursorDecodeException`; valid base64 with malformed JSON
  → `CursorDecodeException`; unknown `v` → `QueryValidationException`;
  `filterHash` is stable for equivalent inputs and differs when any of
  `actor`/`resource`/`from`/`to` differs (including null vs empty).
- `GlobalExceptionHandlerTest` (extend if exists, otherwise add slice test) —
  asserts status codes + envelope shape for both new exceptions.

**DoD.**
- All new unit tests pass; `mvn verify` green.
- No call site throws either exception yet (wiring lives in T5).
- `LayerBoundaryTest` still green.

**Out of scope.** Repository, service orchestration, controller endpoint.

---

## T4 — Repository: keyset query method

**Goal.** Land the single `findPage` JPQL method that backs the read endpoint,
plus its integration tests, before any service uses it.

**Refs.** AC1.2–AC1.6 (filter combinations), AC2.1–AC2.2 (ordering &
determinism), AC3.4 (keyset over `(timestamp, id)`); design §3.1, §3.4, §4,
§6.3.

**Scope.**
- `repository.AuditEventRepository.findPage(...)` per design §6.3 — JPQL with
  null-tolerant filter predicates, `(timestamp, id) < (cursorTs, cursorId)`
  keyset clause, ordered `timestamp DESC, id DESC`, takes a `Pageable` set to
  `limit + 1` rows.

*Tests (Testcontainers integration):*
- `AuditEventRepositoryQueryIT` — for each of: no filters; `actor` only;
  `resource` only; `from` only; `to` only; `from`+`to`; `actor`+`from`+`to`;
  `actor`+`resource`. Seed deterministic data including two events sharing a
  millisecond. Assert returned rows match filter, are ordered by
  `(timestamp DESC, id DESC)`, and the keyset cursor variant returns the
  expected next slice.
- Assert `limit + 1` semantics: when N+1 matching rows exist with
  `Pageable.ofSize(N+1)`, exactly N+1 rows come back; service-layer
  trimming is not the repo's concern but the test pins the contract.

**DoD.**
- All scenarios in `AuditEventRepositoryQueryIT` pass.
- `mvn verify` green.
- Query plan check (manual, in test logs is fine): each scenario uses one of
  the three indexes from design §4 — captured as a comment in the test, not
  a hard assertion.

**Out of scope.** Cursor encoding, has-more decision, validation. Those live
in T5.

---

## T5 — `AuditEventQueryService` and semantic validation

**Goal.** Compose T3 (cursor) and T4 (repo) into the single read entry point
the controller will call, with all semantic validation centralized here.

**Refs.** AC1.6, AC1.9, AC3.5, AC3.6, AC3.7, AC3.8b, AC3.9b, AC3.10; design
§3.4 (has-more), §5.2 (semantic tier), §6.2 (`AuditEventQueryService`,
`QuerySpec`).

**Scope.**
- `service.QuerySpec` — internal record carrying `actor`, `resource`,
  `from`, `to`, `cursor` (decoded `Cursor` or null), `limit`. Constructed by
  the controller after Spring binding; not exposed as a DTO.
- `service.AuditEventQueryService.query(QuerySpec) → AuditEventPage`:
  1. Validate (§5.2): `from >= to` → 422; `limit < 1 || > 200` → 422;
     `actor`/`resource` empty or all-whitespace when present → 422;
     decoded cursor's `v` not in supported set → 422; cursor's `fh` ≠
     `CursorCodec.filterHash(currentFilters)` → 422.
  2. Call `findPage(...)` with `Pageable.ofSize(limit + 1)`.
  3. If `(limit + 1)` rows came back, build `next_cursor` from the
     **`limit`-th** row (not the extra one); else omit.
  4. Map to `AuditEventResponse` via the converter.
- Default `limit` = 50 (constant on the service).

*Tests (unit, Mockito-style with stubbed repo + real `CursorCodec`):*
- `AuditEventQueryServiceTest` covering each validation branch (six 422
  cases), the has-more boundary at exactly `limit` rows (no `next_cursor`),
  has-more at `limit + 1` rows (cursor points to limit-th row), final-page
  empty result, and the default-limit case.

**DoD.**
- All unit tests pass; `mvn verify` green.
- The service has no dependency on Spring MVC / `HttpServletRequest`.
- `LayerBoundaryTest` still green.

**Out of scope.** Controller wiring, end-to-end pagination walks.

---

## T6 — `GET /api/v1/audit-events` controller and end-to-end tests

**Goal.** Expose the endpoint, finalize the parse-tier contract, and prove all
ACs end-to-end on real Postgres.

**Refs.** all AC1.x, AC2.x, AC3.x; design §1 (contract), §1.4 (error mapping),
§5.1 (parse tier), §6.1 (controller).

**Scope.**
- `controller.AuditEventController.query(...)` — `@GetMapping`, parameters
  per design §1.2. `from`/`to` bound as `Instant` (RFC 3339, accepts offsets,
  normalized to UTC by `Instant`); `limit` bound as `Integer` with
  `defaultValue` "50"; `cursor`/`actor`/`resource` as `String`. Builds
  `QuerySpec` (decoding the cursor via `CursorCodec` first, which may throw
  `CursorDecodeException` → 400) and delegates to `AuditEventQueryService`.
- `dto.AuditEventPage` — record `(List<AuditEventResponse> events,
  String nextCursor)` with Jackson naming so it serializes as `next_cursor`.
  Omit `next_cursor` from JSON when null (Jackson `@JsonInclude(NON_NULL)`).

*Tests (Testcontainers integration — `AuditEventQueryIntegrationTest`):*
Group by AC family so failures point at a story:
- **Filters (AC1.1–AC1.6, AC1.10):** matrix of single/combined filters
  returns expected sets; verify the DB rows are byte-identical before and
  after the GET (read-only).
- **Response shape (AC1.7, AC2.4):** every documented field is present,
  RFC 3339 with `Z`. Assert the response timestamp echoes the DB row's
  microsecond precision (6 fractional digits) unchanged; no Jackson
  formatter is configured for `Instant` — pass-through is the contract,
  so a future "fix" that pins millisecond width would regress AC2.4.
- **Parse errors (AC1.8, AC3.8a, AC3.9a):** malformed `from`/`to`/`limit`/
  `cursor` → 400 with envelope.
- **Semantic errors (AC1.9, AC3.5, AC3.8b, AC3.9b):** `from >= to`,
  filter/cursor disagreement, `limit` 0/201, cursor with bumped `v` → 422.
  AC3.5 must cover both asymmetric cases: cursor encoded with a filter
  (e.g. `actor=u_42`) replayed with that filter absent, and cursor encoded
  with no filters replayed with a filter added. Both → 422.
- **Ordering & ties (AC2.1–AC2.3):** seed two events with identical
  `timestamp`, assert ULID tiebreaker.
- **Pagination & stability (AC3.1, AC3.3, AC3.4, AC3.6, AC3.10):** seed N
  events, walk to exhaustion via `next_cursor`, assert union equals seeded
  set, no duplicates, no omissions; `next_cursor` is absent on final page;
  insert M new events between page 1 and page 2, assert page 2 still pulls
  from the original snapshot (stability).
- **Limit edge (AC3.7):** default 50; explicit 200 ok; final-page-equals-
  limit produces no `next_cursor` (boundary at `limit + 1`).
- **Opaque cursor (AC3.2):** clients never need to read it — exercised by
  asserting an arbitrary base64-shaped string round-trips through the codec
  and an arbitrary JSON poked into a cursor produces 400/422 (already in T3,
  re-asserted at HTTP boundary here).

**DoD.**
- `mvn verify` green with all AC families above passing on Testcontainers
  Postgres.
- `LayerBoundaryTest` and ArchUnit suite still green; no new package added.
- Manual smoke (curl) against a locally booted app returns the documented
  shape on a hand-seeded row — recorded as a snippet in PR description, not
  a test.

**Out of scope.** OpenAPI annotations (T7).

---

## T7 — OpenAPI / springdoc annotations

**Goal.** Reflect the new endpoint and changed POST in the generated
`/v3/api-docs` and Swagger UI.

**Refs.** design §1 (contract); commit `e894e35` introduced springdoc, so
the convention already exists in the controller.

**Scope.**
- Annotate `query(...)` with `@Operation`, parameter descriptions,
  documented response shapes (200 `AuditEventPage`, 400/422 envelope).
- Re-annotate the `POST` handler to reflect the new structured body and the
  path change.
- Add `@Schema` descriptions on `ActorRef`, `ResourceRef`,
  `AuditEventResponse`, `AuditEventPage`, `CreateAuditEventRequest`.

**DoD.**
- `GET /v3/api-docs` includes both endpoints under `/api/v1/audit-events`
  with the documented shapes; Swagger UI renders without warnings.
- No behavior change; existing tests still pass.

**Out of scope.** Anything not visible in the OpenAPI document.

---

## T8 — Multi-actor filter: extend the read path to an actor set (atomic)

**Goal.** Extend `GET /api/v1/audit-events` so `actor` accepts a comma-separated list of 1–10 distinct ids with set-membership semantics, dedup, the 10-id cap, cursor replay that is order- and multiplicity-insensitive on the actor set, and a structural-vs-semantic split that reclassifies present-but-empty `actor`/`resource` from 422 to 400. The codec, service, repository, controller, and exception layer all change together because the type of the actor parameter ripples through all of them.

**Refs.**
- requirements AC1.2 (reformulated to set membership), AC1.11 (dedup), AC1.12 (400 on empty value / empty entry / trailing comma), AC1.13 (422 on >10 distinct ids), AC1.14 (400 on empty resource), AC3.11 (cursor `fh` match is set-based), AC3.12 (multi-actor walk: union, no dup, no loss; one event whose `actor.id` matches multiple ids in the list appears exactly once).
- design §1.2 (param table), §1.4 (error table + structural-vs-semantic reframe), §3.3 (`fh` actor segment is sorted-deduped), §4 (`MergeAppend` over the existing `idx_events_actor_ts_id`, no new index), §5.2 (validation order: empty → range/limit → dedup → cap → cursor), §6.1 (controller binds `List<String>`), §6.2 (`QuerySpec.actor: Set<String>`, `CursorCodec` actor-segment construction), §6.3 (`:actors IS NULL OR e.actorId IN :actors`), §8 (multi-actor edge cases).

**Scope.**

*Cursor codec*
- `service.CursorCodec.filterHash` signature changes from `(String actor, String resource, Instant from, Instant to)` to `(Set<String> actors, String resource, Instant from, Instant to)`. Inside, the actor segment is computed as `actors == null ? "" : String.join("", actors.stream().sorted().toList())` over the already-deduplicated set; the rest of the hash construction is unchanged. `null` and the empty set both mean "no actor filter" and must hash identically; the service is responsible for never passing an empty set (it passes `null` when the filter is absent), so the empty-set path is a defensive default, not a documented call site.

*Exception layer*
- `service.EmptyFilterException extends RuntimeException` — thrown for structural failures on filter values: `actor=` empty value, any empty/blank entry in the comma-separated list, trailing comma, or `resource=` empty/blank. Body carries field-level messages compatible with the existing envelope.
- `controller.GlobalExceptionHandler` — add `@ExceptionHandler(EmptyFilterException.class)` → 400 with the existing envelope shape. Existing `QueryValidationException` → 422 handler unchanged.
- `AuditEventQueryService.validate` (or its tier-1 helper) — move the actor/resource empty-string check out of the `QueryValidationException` (422) path and into `EmptyFilterException` (400). Existing tests asserting 422 for those cases are updated to 400 in the same commit (T6 IT + T5 unit).

*`QuerySpec` and service*
- `service.QuerySpec.actor` changes from `String` to `Set<String>` (nullable). The controller hands the raw `List<String>` to the service, which canonicalizes it once (empty-entry rejection → dedup) and stores the result as a `Set<String>` (or `null` when the filter is absent). Holding it as a set makes the dedup structurally permanent for every downstream consumer.
- `AuditEventQueryService.query` / `validate` enforce the validation order pinned in design §5.2: (1) reject empty entries / empty values with `EmptyFilterException`; (2) range and limit checks (unchanged); (3) dedup actor list (no error); (4) if distinct count > 10, throw `QueryValidationException` per AC1.13; (5) cursor decode + version + `fh` check (the `fh` recomputation now passes the deduplicated `Set<String>` to `CursorCodec.filterHash`, automatically getting set-based comparison per AC3.11).
- Existing `filterHash` call sites in `AuditEventQueryService` (currently passing `spec.actor()` as a `String`) update to pass the new `Set<String>` directly.

*Repository*
- `repository.AuditEventRepository.findPage` — rename `@Param("actor") String actor` to `@Param("actors") Collection<String> actors`; in the JPQL, `(:actor IS NULL OR e.actorId = :actor)` becomes `(:actors IS NULL OR e.actorId IN :actors)`. The `IS NULL` check on a collection parameter is supported by Hibernate when `null` is bound. Update the call site in `AuditEventQueryService` to pass `spec.actor()` (the `Set<String>` or `null`). The repository never sees an empty set: an empty `actor=` is rejected upstream by `EmptyFilterException` per §5.2 / §6.3.

*Controller*
- `controller.AuditEventController.query(...)` — change the `actor` parameter from `@RequestParam(required=false) String actor` to `@RequestParam(required=false) List<String> actor`. Spring's default comma-split applies (`?actor=a,b,c` → `["a","b","c"]`; `?actor=` → `[""]`; absence → `null`). The controller forwards the raw list to the service without normalizing — dedup, empty-entry detection, and the cap all live in the service (single owner of canonicalization, per design §6.1).

*Tests (unit, no Spring context):*
- `CursorCodecTest` — extend with: `filterHash` with a single-id set equals the previous `filterHash` with that single id as a String (call-out only if the prior test fixture is asserting against a known byte sequence — otherwise the new test is purely behavioral); `filterHash` is identical across `Set.of("a","b")`, `Set.of("b","a")`, and a dedup'd version of `["a","a","b"]`; `filterHash` differs by one bit when the set differs by one id; `null` and empty set hash to the same "no actor filter" segment.
- `AuditEventQueryServiceTest` — extend with: AC1.11 dedup invariance on result set + on hash; AC1.13 cap (10 distinct → 200, 11 distinct → 422, 12 raw with 4 dups collapsing to 8 distinct → 200); AC1.12 empty value, empty entry, and trailing comma each throw `EmptyFilterException`; AC1.14 `resource=""` throws `EmptyFilterException` (this *changes* the existing 422 expectation); AC3.11 cursor replay accepted under reordered/dup'd actor list, rejected when the actor set differs by one id.

*Tests (Testcontainers integration — extend `AuditEventQueryIntegrationTest`):*
- **AC1.2 reformulated:** `actor=a1,a2` returns events whose `actor.id ∈ {a1,a2}`; assert the result equals the union of the two single-actor queries.
- **AC1.11 dedup:** `actor=a1,a1,a2` and `actor=a1,a2` return byte-identical responses (including `next_cursor`).
- **AC1.12 structural rejections:** `actor=`, `actor=a1,,a2`, `actor=a1,`, `actor=,a1` each return 400 with envelope; no partial page.
- **AC1.13 cap:** 10 distinct ids → 200; 11 distinct ids → 422 with a message naming the cap; 12 raw with collisions collapsing to 8 distinct → 200.
- **AC1.14 resource symmetry:** `resource=` → 400 with envelope (this replaces the existing 422 expectation in the same commit).
- **AC3.11 cursor order-insensitivity:** mint a cursor with `actor=a1,a2`, replay with `actor=a2,a1` → 200; replay with `actor=a1,a2,a3` → 422; replay with `actor=a1` → 422.
- **AC3.12 multi-actor walk:** seed N events across 3 actors `{a1, a2, a3}` plus a few rows whose `actor.id` is in two-of-three (only possible if seed semantics permit multiple actors per row — if not, just exercise that a single full walk via `next_cursor` over `actor=a1,a2,a3` returns the union with no duplicates and no losses; AC3.12's "appears exactly once" clause is then enforced by the JPQL's `IN`-based row identity at the DB layer and pinned by asserting `distinct(events.id).size() == events.size()` per page and across all pages).
- **Index plan (smoke):** capture the `EXPLAIN` of one multi-actor query in test logs and grep for `Index Scan using idx_events_actor_ts_id` (one per actor) plus `Merge Append` (or `BitmapOr` on older planners) — a soft assertion via log inspection, matching the precedent in T4. If the planner picks a different shape, the test logs the plan and continues; this catches regressions to seq-scan without flaking on Postgres minor-version planner shifts.

**DoD.**
- `mvn verify` green, with both new IT scenarios and the migrated unit tests passing on Testcontainers Postgres.
- Every new AC (1.11, 1.12, 1.13, 1.14, 3.11, 3.12) has at least one assertion in the unit or integration tests above that would fail if the corresponding behavior regressed; AC1.2 (reformulated) is covered by the existing single-actor tests *plus* the new multi-actor union test.
- Existing tests that previously asserted 422 for empty `actor`/`resource` have been atomically updated to 400 and reference `EmptyFilterException`. No test references the old `filterHash(String, …)` signature.
- `LayerBoundaryTest` and the rest of the ArchUnit suite still green; no new package added.
- Manual curl smoke against a locally booted app, recorded in the PR description: `?actor=a,b` returns the expected union; `?actor=` returns 400; `?actor=` with 11 ids returns 422; a cursor minted under `actor=a,b` is honored when replayed as `actor=b,a` and rejected when replayed as `actor=a`.

**Out of scope.**
- OpenAPI annotations (T9).
- Multi-value lists on `resource`, `from`, `to` (requirements.md "Out of scope").
- Any change to the index set in design §4 — the existing `idx_events_actor_ts_id` is what the multi-actor plan rides on; T8 must not add or drop indexes. If profiling later shows the `MergeAppend` plan is hot enough to justify a different shape, that is a separate task with its own migration.

---

## T9 — OpenAPI / springdoc updates for the multi-actor parameter

**Goal.** Reflect the new `actor` list semantics, the 10-id cap, and the empty-filter 400 reclassification in the generated `/v3/api-docs` and Swagger UI.

**Refs.** design §1.2 (param table), §1.4 (error table); requirements AC1.2 (reformulated), AC1.11–14, AC3.11; T7 precedent (the `@Operation`/`@Parameter`/`@Schema` conventions already in place on the controller).

**Scope.**
- `@Parameter` on `actor` in `AuditEventController.query` — typed as array of strings (springdoc renders this as `style=form, explode=false` for the comma-separated form by default), description text covering: "one or more distinct actor ids; duplicates are silently dropped; maximum 10 distinct ids per request; empty value or empty entry rejected with 400; >10 distinct ids rejected with 422". Provide an example `u_42,svc_billing`.
- `@Parameter` on `resource` — description updated to note that an empty value now returns 400 (was 422 in the T7 doc).
- Operation response docs — under 400, add the bullet "`actor` empty value or empty entry in the comma-separated list; `resource` empty value"; under 422, add the bullet "`actor` list contains more than 10 distinct ids after dedup".
- No `@Schema` changes on response components: `AuditEventResponse`, `ActorRef`, `ResourceRef`, `AuditEventPage`, `CreateAuditEventRequest` are all unaffected.

**DoD.**
- `GET /v3/api-docs` renders `actor` as an array-typed query parameter with the documented constraints and example; Swagger UI renders without warnings.
- The documented 400 / 422 lists for the `GET` operation match design §1.4 exactly (no row in the design table is missing from the operation doc, and no doc row is missing from the table).
- No behavior change; all T1–T8 tests still pass.

**Out of scope.** Anything not visible in the generated OpenAPI document; any change to runtime validation behavior (that lives in T8).
