# Plan Delta Log

Short notes on what changed between plan iterations and why.
For full plan content see the versioned files (`v1.md`, `v2.md`, …).

## v1 → v2

### ULID library version

- **Issue.** v1 said `com.github.f4b6a3:ulid-creator` "5.x" — a range, not a
  pin. `tasks.md` T1 also left the version unspecified ("or equivalent —
  pick one small dep"). Two artifacts both deferring the choice means the
  implementer would invent it on the fly, and a future re-run could pick
  a different version.
- **Fix.** Pinned to `com.github.f4b6a3:ulid-creator:5.2.3` in both
  `tasks.md` T1 and `v2.md` Commit 1.

### Mockito vs hand-rolled stubs in T5 unit tests

- **Issue.** v1 specified hand-rolled stubs for
  `AuditEventQueryServiceTest`, with the rationale "keeps the repo's
  minimal-test-deps pattern." This contradicted `tasks.md` T5, which
  reads *"Tests (unit, Mockito-style with stubbed repo + real
  `CursorCodec`)."* Plan and tasks disagreed on a real implementation
  detail.
- **Fix.** v2 reverts to Mockito, aligning with `tasks.md`. Mockito is
  already on the classpath via `spring-boot-starter-test`, so no new
  dependency is needed. Test uses
  `@ExtendWith(MockitoExtension.class)`, `@Mock AuditEventRepository`,
  real `CursorCodec`, real `AuditEventConverter`.

## v2 → v3

### `UlidFactory.fromTimestamp(Instant)`

- **Issue.** v2 spec'd `UlidFactory` with only `String next()`. T2's
  backfill needs to assign ULIDs to historical rows whose time component
  equals each row's original `timestamp` — otherwise the post-migration
  `id ASC` order would not match the pre-migration `timestamp ASC` order,
  and AC2.1's tiebreaker contract would silently shift for those rows.
- **Fix.** v3 adds `fromTimestamp(Instant)` alongside `next()`. The new
  method uses `UlidCreator.getUlid(long timeMillis)` (random payload,
  caller-supplied time component); `next()` keeps using the monotonic
  factory for runtime inserts.

### V2 migration as a Flyway Java migration

- **Issue.** v2 spec'd the V2 migration as
  `src/main/resources/db/migration/V2__query_api_model.sql`. With
  `UlidFactory` living in Java, a SQL migration would either need a
  parallel ULID generator implemented in PL/pgSQL (two sources of truth
  for ULID generation) or a workaround like a temporary `ulid` extension.
- **Fix.** v3 makes V2 a Flyway Java migration at
  `src/main/java/com/sam/auditlog/db/migration/V2__query_api_model.java`
  (extends `BaseJavaMigration`). The migration instantiates `UlidFactory`
  directly and calls `fromTimestamp(timestamp)` per row during the
  INSERT-from-old. `application.yml` gets
  `spring.flyway.locations: classpath:db/migration,classpath:com/sam/auditlog/db/migration`
  so V1 (SQL, unchanged) and V2 (Java) are both discovered.
