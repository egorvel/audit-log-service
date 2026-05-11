# Query API on `audit_events` — Design

## 1. API contract

### 1.1 Resource path

Both the read and the write paths live under one resource:

```
GET  /api/v1/audit-events
POST /api/v1/audit-events
```

This replaces both `GET /api/v1/events` and `POST /api/v1/events`. The unified path keeps versioning (`/api/v1`) and uses the same noun the domain uses (`audit-events`). Back-compat is waived for this task, so existing clients of `/api/v1/events` are expected to migrate.

### 1.2 Query parameters

| Name       | Type             | Required | Default | Notes                                                            |
| ---------- | ---------------- | -------- | ------- | ---------------------------------------------------------------- |
| `actor`    | string           | no       | —       | Matches `actor.id` exactly. Empty string is a 422.               |
| `resource` | string           | no       | —       | Matches `resource.id` exactly. Empty string is a 422.            |
| `from`     | RFC 3339 instant | no       | —       | Inclusive lower bound. Parse failure ⇒ 400.                      |
| `to`       | RFC 3339 instant | no       | —       | Exclusive upper bound. Parse failure ⇒ 400.                      |
| `cursor`   | opaque string    | no       | —       | See §3. Undecodable ⇒ 400; decodable but unhonorable ⇒ 422.      |
| `limit`    | integer          | no       | 50      | Non-integer ⇒ 400; out of [1, 200] ⇒ 422.                        |

Filters are AND-combined. All four data filters may be omitted; the endpoint then paginates the entire stream (requirements §AC1.6).

### 1.3 Success response

`200 OK`, `Content-Type: application/json`:

```json
{
  "events": [
    {
      "id": "01HE3XJ7N2K9V0R1B6T8Q4WMZ9",
      "timestamp": "2026-04-17T11:02:14.123456Z",
      "actor":    { "id": "u_42",       "type": "user" },
      "resource": { "id": "9f3b...",    "type": "order" },
      "action":   "order.refunded",
      "outcome":  "success",
      "context":  { "amount": 1299, "currency": "EUR" }
    }
  ],
  "next_cursor": "eyJ2IjoxLCJ0cyI6Ii4uIiwiaWQiOiIuLiIsImZoIjoiLi4ifQ"
}
```

`next_cursor` is omitted (or explicitly `null`) on the final page (§AC3.6). The `events` array is empty on a query with no matches.

### 1.4 Error responses

All errors share one envelope (already established by `GlobalExceptionHandler`):

```json
{
  "timestamp": "2026-05-09T12:34:56.000Z",
  "status":    400,
  "error":     "<short reason>",
  "errors":    ["<field>: <detail>", ...]
}
```

| Status | When                                                                                                                              | Source                  |
| ------ | --------------------------------------------------------------------------------------------------------------------------------- | ----------------------- |
| 400    | `from` or `to` not RFC 3339 (§AC1.8)                                                                                              | Spring param-binding    |
| 400    | `limit` not parseable as integer (§AC3.8a)                                                                                        | Spring param-binding    |
| 400    | `cursor` cannot be base64/JSON-decoded (§AC3.9a)                                                                                  | service                 |
| 422    | `from >= to` (§AC1.9)                                                                                                             | service                 |
| 422    | `limit` parses but is `< 1` or `> 200` (§AC3.8b)                                                                                  | service                 |
| 422    | `cursor` decodes but its filter-hash disagrees with the current request, or its schema version is unknown (§AC3.5, §AC3.9b)       | service                 |
| 422    | `actor` or `resource` present as empty string                                                                                     | service                 |

400 is reserved for *parse* failures (the request can't be turned into typed values). 422 is for requests that parse cleanly but are semantically rejected. The `GlobalExceptionHandler` is extended with a `@ResponseStatus(UNPROCESSABLE_ENTITY)` handler for a new `QueryValidationException` so the layer boundary stays clean (validation lives in the service; the controller translates).

## 2. Data model

### 2.1 New schema

```sql
CREATE TABLE audit_events (
    id            CHAR(26)    PRIMARY KEY,            -- ULID, Crockford-Base32
    timestamp     TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_id      TEXT        NOT NULL,
    actor_type    TEXT        NOT NULL,
    resource_id   TEXT        NOT NULL,
    resource_type TEXT        NOT NULL,
    action        TEXT        NOT NULL,
    outcome       TEXT        NOT NULL CHECK (outcome IN ('success','denied','error')),
    context       JSONB       NOT NULL DEFAULT '{}'::jsonb
);
```

`id` is `CHAR(26)` (the ULID's canonical Crockford-Base32 representation). Lexicographic byte order on this representation matches ULID's intended monotonic order, so it is directly usable as a sort key and as a tiebreaker without conversion at the API boundary.

The triggers and role grants from `V1__create_audit_events.sql` (`audit_events_no_update`, `audit_events_no_delete`, `audit_events_no_truncate`, `GRANT SELECT, INSERT`) are recreated against the new table so the append-only invariant is preserved.

### 2.2 ULID generation

ULIDs are generated **at the application layer** at insert time (not by the DB). Reasons:

1. The DB-assigned `timestamp` and the ULID's embedded timestamp must not drift apart in a way that breaks ordering. We let the DB own `timestamp` (per the existing invariant) and let the app own `id`. Both are server-assigned in the sense that neither comes from the client.
2. Generating ULID app-side keeps the entity assembly straightforward (`new AuditEvent(...)` produces an entity already complete except for `timestamp`).

A `UlidFactory` (single bean, monotonic per JVM) is added to the service layer. Implementation can wrap a known library (e.g. `f4b6a3/ulid-creator`) — choice belongs in `tasks.md`.

### 2.3 Migration strategy (Flyway Java migration `V2__query_api_model.java`)

The migration is implemented as a Flyway `BaseJavaMigration` (not a `.sql` file) so the INSERT-from-old step can call `UlidFactory.fromTimestamp(...)` directly per row. A `.sql` migration would have to re-implement ULID generation in PL/pgSQL or pull in a temporary `ulid` extension — both create a second source of truth for ULID generation alongside the Java `UlidFactory`. Flyway is configured with `spring.flyway.locations: classpath:db/migration,classpath:com/sam/auditlog/db/migration` so V1 (SQL) and V2 (Java) are both discovered.

The model change cannot be expressed as plain `ALTER TABLE … UPDATE …` because the no-update trigger is part of the contract on the `audit_events` table. The migration therefore uses **table replacement via INSERT-only**:

1. Create `audit_events_new` with the new schema (no triggers yet).
2. `INSERT INTO audit_events_new (...) SELECT ... FROM audit_events`, deriving columns:
   - `id`         ← a freshly generated ULID per row, seeded from the row's `timestamp` so historical rows retain their timestamp ordering.
   - `actor_id`   ← old `actor`.
   - `actor_type` ← `'unknown'` (resolves the `actor.type` open question from `requirements.md`; see note below).
   - `resource_id` ← old `resource`.
   - `resource_type` ← `'unknown'`.
3. Drop the old `audit_events` (allowed: it is a DDL replacement of a table, not a DML mutation of live rows).
4. `ALTER TABLE audit_events_new RENAME TO audit_events`.
5. Re-create the no-update / no-delete / no-truncate triggers and the `GRANT SELECT, INSERT` to `audit_app`.
6. Create the indexes from §4.

This procedure performs **no `UPDATE` and no `DELETE` against live rows**: it only INSERTs into a fresh table and then DROPs the old one as part of the schema-replacement DDL. Append-only is honored in spirit and in letter (AGENTS.md §Invariants).

**Open question resolution.** `requirements.md` left the legacy `actor.type` / `resource.type` value to be decided here. The migration assigns the literal string `"unknown"` for pre-migration rows. Rationale: there is no enforced parsing convention in V1 data (e.g. `order/9f3b…` vs `order:42` vs free-form), so any guess would be wrong for some producers. `"unknown"` is honest and survives later remediation by producers re-emitting events.

### 2.4 Knock-on changes to the write path

The `POST /api/v1/audit-events` request body changes shape to accept structured fields:

```json
{
  "actor":    { "id": "u_42", "type": "user" },
  "resource": { "id": "9f3b…", "type": "order" },
  "action":   "order.refunded",
  "outcome":  "success",
  "context":  { ... }
}
```

`@NotBlank` constraints move onto the nested `id` and `type` fields. This is a breaking change to the write contract; it is in scope only because the model change demands it, and back-compat is waived.

## 3. Pagination strategy

### 3.1 Sort & determinism

Events are returned sorted by `(timestamp DESC, id DESC)` (§AC2.1). The tiebreaker is **required, not optional**:

- Two events can share the same `timestamp` (millisecond precision is not enough to guarantee uniqueness under burst writes).
- Without a tiebreaker, a keyset cursor of "events with `timestamp < T`" would either skip or duplicate every row whose timestamp equals `T`, depending on which side of the boundary it lands on.
- `id` (ULID) is unique, lexicographically sortable, and monotonic within a millisecond, so `(timestamp, id)` is a total order. This gives the determinism that AC2.2 and AC3.10 (no loss / no duplication on a full walk) demand.

### 3.2 Keyset (cursor) pagination

The endpoint uses **keyset pagination** rather than offset/limit. Reasoning:

- **Stability under concurrent inserts (§AC3.4, §AC3.10).** New events land at the *head* of a `timestamp DESC` ordering; a keyset predicate `(timestamp, id) < (cursor.ts, cursor.id)` is unaffected by them, so a walk started at `T0` continues to enumerate the snapshot it began with. Offset/limit would skip events: if 5 new events arrive between page 1 and page 2, page 2 starting at `OFFSET=50` would skip the 5 oldest events from page 1's snapshot.
- **Cost.** Keyset is `O(limit)` per page given the indexes in §4; offset/`OFFSET=N` degrades to `O(N + limit)`.
- **Cursor opacity (§AC3.2).** Encoding the keyset state in an opaque token lets the server change the encoding (e.g. add fields, change tiebreaker) without breaking clients.

### 3.3 Cursor format

The cursor is base64url(JSON), no signing:

```json
{
  "v":  1,                                  // schema version
  "ts": "2026-04-17T11:02:14.123Z",         // last returned timestamp (RFC 3339)
  "id": "01HE3XJ7N2K9V0R1B6T8Q4WMZ9",       // last returned ULID
  "fh": "sha256:…"                          // hash of the filter set
}
```

- `v` lets us reject cursors from incompatible schema versions with **422** (§AC3.9b).
- `fh` is `sha256(actor || "" || resource || "" || from || "" || to)` (`` is unit separator, not in any sane filter value). The server recomputes `fh` from the current request and rejects mismatch with **422** (§AC3.5). This catches both deliberate tampering and accidental cross-pasting of cursors between different queries; signing would add operational cost (key rotation) for a token that already grants no privilege beyond what its filter set implies.
- `ts`+`id` are the keyset state.

The cursor is **not encrypted**. It does not contain user data the client did not already supply.

### 3.4 Has-more detection

To set `next_cursor` correctly without an extra count query, the repository fetches `limit + 1` rows. If the extra row exists, the response carries the first `limit` rows and a `next_cursor` derived from the *last returned* row (the `limit`-th, not the extra one); otherwise no `next_cursor` is emitted. This avoids the false-positive case where the final page exactly equals `limit`.

## 4. Indexes

The query is `WHERE … AND (timestamp, id) < (?,?) ORDER BY timestamp DESC, id DESC LIMIT ?`. Indexes are chosen so the keyset predicate plus the sort can both be served from index order.

```sql
CREATE INDEX idx_events_ts_id        ON audit_events (timestamp DESC, id DESC);
CREATE INDEX idx_events_actor_ts_id  ON audit_events (actor_id, timestamp DESC, id DESC);
CREATE INDEX idx_events_res_ts_id    ON audit_events (resource_id, timestamp DESC, id DESC);
```

Coverage:

| Filters present                  | Index used                  |
| -------------------------------- | --------------------------- |
| (none) / `from` / `to` / both    | `idx_events_ts_id`          |
| `actor` (± time range)           | `idx_events_actor_ts_id`    |
| `resource` (± time range)        | `idx_events_res_ts_id`      |
| `actor` + `resource`             | `idx_events_actor_ts_id` *  |

\* The combined `actor` + `resource` case uses the actor index and post-filters by `resource_id`. A separate composite would be added only if profiling shows this case is hot — single-actor-single-resource queries are typically narrow enough that the actor index alone is fine.

`actor_type` and `resource_type` are not filterable in v1 (out of scope), so they get no indexes. The pre-existing standalone `idx_audit_events_action` and `idx_audit_events_actor` from V1 are dropped — they no longer match the access pattern.

Indexes are created in the same `V2__query_api_model.java` migration as the table, so the cluster moves atomically from the V1 to the V2 access pattern.

## 5. Validation rules

Validation is a two-tier split aligned with §1.4:

### 5.1 Tier 1 — parse (controller / Spring binding)

Implemented by Spring's parameter binding plus the existing `GlobalExceptionHandler`. Failures here surface as **400**:

- `from`, `to`: bound as `Instant`. Invalid format ⇒ `MethodArgumentTypeMismatchException` ⇒ 400.
- `limit`: bound as `int`. Non-numeric ⇒ 400.
- `cursor`: bound as `String`; decoding (base64 + JSON) happens in the service. A decode error throws `CursorDecodeException` (extends `IllegalArgumentException`) which a new handler maps to **400**.

### 5.2 Tier 2 — semantic (service)

All semantic checks throw `QueryValidationException`, mapped to **422** by a new handler. They run *after* parse:

- `from >= to` when both are present.
- `limit < 1` or `limit > 200`.
- `actor`/`resource` present and empty (`""`) or all-whitespace.
- Cursor `v` not in the supported set.
- Cursor `fh` does not equal the recomputed hash of the current request's filter set.

Validation lives in `AuditEventQueryService.validate(QuerySpec)`; the controller does not branch on these conditions. This keeps the layer boundary that `LayerBoundaryTest` enforces (controller depends on service, not the other way around).

## 6. Integration with API / domain / infrastructure layers

The existing layout (AGENTS.md §Layout, enforced by `LayerBoundaryTest`) is preserved. New types live in the existing packages:

### 6.1 API layer (`controller`, `dto`)

- `AuditEventController` — `@RequestMapping` is moved from `/api/v1/events` to `/api/v1/audit-events`. The existing `POST` handler stays in place (now consuming the new structured DTO). A `@GetMapping` `query(...)` is added with `@RequestParam` arguments mapped 1:1 to §1.2. Returns `AuditEventPage`.
- `dto.AuditEventPage` — record `(List<AuditEventResponse> events, String nextCursor)`. `nextCursor` is rendered as `next_cursor` (Jackson naming).
- `dto.AuditEventResponse` — updated to:
  ```java
  record AuditEventResponse(
      String id,                     // ULID
      Instant timestamp,
      ActorRef actor,                // {id, type}
      ResourceRef resource,          // {id, type}
      String action,
      Outcome outcome,
      Map<String, Object> context) {}
  ```
- `dto.ActorRef`, `dto.ResourceRef` — small records with `@NotBlank` on both fields (used by both read and write paths).
- `dto.CreateAuditEventRequest` — actor/resource fields become `ActorRef`/`ResourceRef`. `@Valid` cascades into them.
- `controller.GlobalExceptionHandler` — gains handlers for `QueryValidationException` (→ 422) and `CursorDecodeException` (→ 400). Body shape unchanged.

### 6.2 Domain layer (`model`, `service`, `converter`)

- `model.AuditEvent` — replaces `Long id` with `String id` (ULID, assigned at construction); replaces flat `actor`/`resource` strings with `actorId`, `actorType`, `resourceId`, `resourceType`. Stays immutable; `@Generated(INSERT)` on `timestamp` is preserved. The constructor's existing required-field checks (`Objects.requireNonNull(actor, …)` etc.) are tightened: each `_id` and `_type` is required (AGENTS.md "actor is required" continues to hold, now applied to `actor.id`).
- `service.AuditEventQueryService` — new sibling of `AuditEventService`. Owns `query(QuerySpec) → AuditEventPage`, parses+validates the cursor, dispatches to the repository, and decides `next_cursor`. Splitting query off keeps the existing `record(...)` write path uncluttered and matches the typical CQRS-shaped split most teams adopt as audit logs grow read-heavy.
- `service.QuerySpec` — internal record mirroring §1.2 plus a decoded `Cursor` value object. Not exposed in DTOs.
- `service.CursorCodec` — encode/decode + filter-hash computation. Pure (no Spring).
- `service.UlidFactory` — Spring `@Component`; injected wherever an `AuditEvent` is constructed.
- `converter.AuditEventConverter` — `toResponse(AuditEvent)` builds the new nested DTO; `toEntity(CreateAuditEventRequest)` injects an `id` from `UlidFactory`.

### 6.3 Infrastructure layer (`repository`, migrations)

- `repository.AuditEventRepository` — `findAllByOrderByIdDesc(...)` is removed (replaced). New method:
  ```java
  @Query(value = """
      SELECT e FROM AuditEvent e
       WHERE (:actor    IS NULL OR e.actorId    = :actor)
         AND (:resource IS NULL OR e.resourceId = :resource)
         AND (:from     IS NULL OR e.timestamp >= :from)
         AND (:to       IS NULL OR e.timestamp <  :to)
         AND (:cursorTs IS NULL OR e.timestamp <  :cursorTs
                                OR (e.timestamp = :cursorTs AND e.id < :cursorId))
       ORDER BY e.timestamp DESC, e.id DESC
      """)
  List<AuditEvent> findPage(...,  Pageable pageable);
  ```
  `Pageable` carries `limit + 1` (see §3.4).
- `com/sam/auditlog/db/migration/V2__query_api_model.java` — the Flyway Java migration described in §2.3.
- The least-privilege grant (`GRANT SELECT, INSERT`) is preserved on the new table, so the read path uses `SELECT` and never needs additional privileges.

### 6.4 Tests (conventions per AGENTS.md §Testing)

- Unit (no Spring context): `CursorCodecTest`, `QueryValidationTest`, `AuditEventConverterTest` (extend the existing one for the new shape).
- Integration (Testcontainers): `AuditEventQueryIntegrationTest` covering each AC family — filter combinations, ordering with same-millisecond ties, cursor walk reaching exactly the snapshot taken at start (concurrent insert simulated by interleaving INSERTs between pages), 400 vs 422 boundaries, V2 migration applied cleanly on top of V1 fixture data.
- ArchUnit: no rule changes needed — all new classes live in existing layers.

## 7. AGENTS.md alignment

| AGENTS.md rule                                                 | How this design honors it                                                                                                        |
| -------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| Append-only events table                                       | V2 migration uses INSERT into a fresh table + DDL drop/rename; no `UPDATE`/`DELETE` against live rows. Triggers re-installed.    |
| Events are immutable                                           | Read path never mutates entities; `AuditEvent` constructor still copies `context` defensively; query results pass through `toResponse` unchanged. |
| `timestamp` is server-assigned                                 | Unchanged — DB default `now()` and `@Generated(INSERT)` on the entity. ULID `id` is also server-assigned (app-side).             |
| `actor` is required                                            | Both `actor.id` and `actor.type` are `NOT NULL` in DB and `@NotBlank` in the DTO; service rejects empty `actor` query param (422). |
| Migrations are append-only files                               | New change goes in `V2__query_api_model.java` (Flyway Java migration). `V1__create_audit_events.sql` is not edited.              |
| App role privileges = `INSERT` + `SELECT`                      | Re-granted on the renamed table by V2; query path uses `SELECT` only.                                                            |
| Java 21, Spring Boot 3, Maven; PostgreSQL, Flyway; Spring Data JPA; Testcontainers | All new code uses these; no new framework introduced. ULID library is a single small dep, picked in `tasks.md`.                  |
| Layer layout (`controller` / `service` / `repository` / …)     | New classes go into the existing packages; `LayerBoundaryTest` continues to pass without rule changes.                           |
| Prefer existing patterns; smallest change                      | Reuse `GlobalExceptionHandler`, the existing controller class, and the existing converter. Add `AuditEventQueryService` rather than overloading the write service, only because the read concerns (cursors, validation, pagination) are independent enough that bundling would obscure both. |
| Spec is the source of truth                                    | This doc derives every choice from a labeled requirement (§AC… references) so the link is auditable.                             |

## 8. Edge cases worth calling out

- **Empty result set.** `events: []`, no `next_cursor`. No special handling; falls out of the `limit + 1` rule.
- **Final page exactly fills `limit`.** Avoided by fetching `limit + 1` and emitting `next_cursor` only when the extra row exists.
- **Same-millisecond burst.** Tiebreaker on `id` (ULID) gives a total order; cursors land between events deterministically.
- **Clock regression.** Server timestamps are sourced from PostgreSQL `now()`; if the wall clock moves backward, ordering could invert briefly. The ULID tiebreaker still produces a total order, but the "newest first" interpretation may briefly disagree with insertion order. Out of scope to fix; acknowledged.
- **Cursor outliving a schema bump.** The `v` field lets V3 reject V1 cursors with 422 instead of returning subtly wrong data.
- **Tampered cursor.** Either decode fails (400) or the filter-hash check fails (422). Either way, no partial page is emitted (§AC3.9).
- **`from` or `to` far in the future / past.** Treated as ordinary range filters — no rejection; the result set is just empty.
