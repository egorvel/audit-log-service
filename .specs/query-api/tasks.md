# Query API on `audit_events` — Tasks

Each task is one safe commit: the tree compiles, all tests pass, and the
runtime state (DB schema ↔ JPA model) is consistent at the end of the task.
References use §-style markers from `requirements.md` (AC…) and `design.md` (§…).

## Dependency graph

```
T1 ── T2 ── T4 ── T5 ── T6 ── T7
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

T2 and T3 are independent and can land in either order; everything from T4
onward depends on T2; T5 depends on both T3 and T4.

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
