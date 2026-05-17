# Query API on `audit_events`

## Context

An API to read audit events should be implemented for the three primary consumer roles of the audit log:

- **Compliance officer** — needs to confirm or refute that a specific actor performed a specific action on a specific resource within an audit window.
- **SRE** — during an incident, needs to reconstruct the chronological sequence of actions that touched a given resource.
- **Security analyst** — needs to walk a large result set (potentially tens of thousands of events) without losing or duplicating events even while new events are still being written.

This feature introduces a single read-only HTTP endpoint that lets these roles query events by `actor`, `resource`, and time range, with cursor-based pagination. The `actor` filter accepts either a single actor id or a comma-separated list of up to 10 actor ids, so that a compliance officer can confirm or refute an action attributable to any one of several principals (e.g. a user and a service account they impersonated) in a single request, and so that a security analyst can walk the union of events for a small cohort of suspects without N round trips.

It also requires a change to the persisted event shape. The current schema stores `id` as a numeric DB identity and `actor`/`resource` as opaque flat strings. The query response contract requires:

- `id` exposed as an externally stable, sortable, string identifier (ULID).
- `actor` and `resource` exposed as structured objects with separate `id` and `type`.

These fields cannot be reliably reconstructed at the response boundary from the current flat strings (there is no enforced parsing convention), so the model itself must change. The append-only invariant on the `audit_events` table is preserved: the migration introduces a new schema via Flyway and ports existing rows by inserting them into a fresh table that atomically replaces the old one. No `UPDATE` or `DELETE` statements run against live event rows.

## User stories

### Story 1 — Compliance officer: confirm or refute an action

> *As a compliance officer, I want to query audit events by one or more actors, a resource, and a time window, so that I can confirm or refute whether a specific action was performed during an audit — including audits that span a small group of related principals (e.g. a user plus the service accounts that acted on their behalf).*

**Acceptance criteria (EARS):**

- **AC1.1 — Ubiquitous.** The system shall expose `GET /api/v1/audit-events` returning a JSON page of events matching the provided filters.
- **AC1.2 — Event-driven.** When the request includes `actor=<id-list>`, where `<id-list>` is a comma-separated list of one or more actor ids, the system shall return only events whose `actor.id` is contained in `<id-list>` (set membership; a single id is the one-element case).
- **AC1.3 — Event-driven.** When the request includes `resource=<id>`, the system shall return only events whose `resource.id` equals `<id>`.
- **AC1.4 — Event-driven.** When the request includes `from=<ts>`, the system shall return only events with `timestamp >= <ts>`.
- **AC1.5 — Event-driven.** When the request includes `to=<ts>`, the system shall return only events with `timestamp < <ts>` (half-open interval).
- **AC1.6 — Ubiquitous.** The system shall combine all provided filters with logical AND.
- **AC1.7 — Ubiquitous.** Each event in the response shall include `id` (ULID string), `timestamp` (RFC 3339, UTC, `Z`-suffixed), `actor` (`{id, type}`), `resource` (`{id, type}`), `action`, `outcome`, and `context`.
- **AC1.8 — Unwanted.** If `from` or `to` is not a valid RFC 3339 timestamp, then the system shall reject the request with HTTP 400 and not return any events.
- **AC1.9 — Unwanted.** If `from` is provided and `to` is provided and `from >= to`, then the system shall reject the request with HTTP 422 (the timestamps parse correctly but the range is semantically invalid).
- **AC1.10 — Ubiquitous.** The system shall not mutate any event as part of serving a query (read-only).
- **AC1.11 — Ubiquitous.** When `actor=<id-list>` contains duplicate ids, the system shall deduplicate them before filter evaluation and before applying the cap in AC1.13; duplicates shall have no effect on which events are returned.
- **AC1.12 — Unwanted.** If `actor` is provided with an empty value (`actor=`) or the comma-separated list contains an empty entry (e.g. `actor=a1,,a2` or a trailing comma `actor=a1,`), then the system shall reject the request with HTTP 400 and not return any events.
- **AC1.13 — Unwanted.** If the deduplicated actor list (per AC1.11) contains more than 10 distinct ids, then the system shall reject the request with HTTP 422 and not return any events (the list parses as a syntactically valid set but exceeds the per-request cap).
- **AC1.14 — Unwanted.** If `resource` is provided with an empty value (`resource=`), then the system shall reject the request with HTTP 400 and not return any events (symmetric with AC1.12 for `actor`: a present-but-empty filter carries no information and is treated as a structural failure, not a semantic one).

### Story 2 — SRE: reconstruct the timeline of a resource during an incident

> *As an SRE, I want to retrieve every audit event that touched a given resource within an incident window, so that I can reconstruct the order in which actions occurred.*

**Acceptance criteria (EARS):**

- **AC2.1 — Ubiquitous.** The system shall return events ordered by `timestamp` descending, with `id` descending as a tiebreaker for events sharing a timestamp.
- **AC2.2 — Ubiquitous.** The ordering shall be total and deterministic: two requests that observe the same set of stored events shall return them in the same order.
- **AC2.3 — Event-driven.** When a request specifies only `resource` and a time range, the system shall return every stored event matching those filters across one or more pages.
- **AC2.4 — Ubiquitous.** The system shall preserve the server-assigned `timestamp` of each event in the response without rounding or reformatting beyond the documented RFC 3339 representation.

### Story 3 — Security analyst: paginate a large result set without loss or duplication

> *As a security analyst, I want to walk every page of a large query result without losing or duplicating events, so that I can rely on the audit log as evidence.*

**Acceptance criteria (EARS):**

- **AC3.1 — Ubiquitous.** The system shall support cursor-based pagination via a `cursor` query parameter and a `next_cursor` field in the response.
- **AC3.2 — Ubiquitous.** The system shall accept any `next_cursor` value it previously emitted when submitted unchanged as the next request's `cursor` (subject to AC3.5/AC3.9b), and the cursor's internal byte layout shall remain an undocumented server-side detail that may evolve across releases (signaled by the embedded schema version `v`).
- **AC3.3 — Event-driven.** When the request includes `cursor=<c>`, the system shall return the next page of events strictly after the position encoded by `<c>` under the same ordering as AC2.1.
- **AC3.4 — Ubiquitous.** The system shall encode the cursor over the `(timestamp, id)` pair of the last returned event, so that pagination is stable under concurrent inserts: events newly written after a page was returned shall not appear on subsequent pages of the same walk, and no event already returned shall reappear.
- **AC3.5 — Event-driven.** When the request includes `cursor`, the system shall require the request's filter set (`actor`, `resource`, `from`, `to`) to match the filter set encoded into the cursor's originating request; if they disagree — including the case where a filter is now absent that was present originally, or vice versa — the system shall reject the request with HTTP 422 (all parameters parse correctly but their combination is inconsistent).
- **AC3.6 — Ubiquitous.** The system shall include `next_cursor` in the response if and only if more events exist after the returned page; the field shall be absent (or `null`) on the final page.
- **AC3.7 — Ubiquitous.** The system shall accept an optional `limit` query parameter; when omitted, the page size shall default to 50; the maximum allowed value shall be 200.
- **AC3.8a — Unwanted.** If `limit` is not parseable as an integer, then the system shall reject the request with HTTP 400.
- **AC3.8b — Unwanted.** If `limit` parses as an integer but is not strictly positive or exceeds 200, then the system shall reject the request with HTTP 422.
- **AC3.9a — Unwanted.** If `cursor` cannot be decoded as a valid cursor token, then the system shall reject the request with HTTP 400 and not return a partial page.
- **AC3.9b — Unwanted.** If `cursor` decodes successfully but references a position the server cannot honor (e.g. encoded under an incompatible schema version), then the system shall reject the request with HTTP 422 and not return a partial page.
- **AC3.10 — Event-driven.** When the same query is replayed page by page using each returned `next_cursor` until exhaustion, the union of returned events shall equal the set of stored events that matched the filters at the time the first page was served, with no duplicates and no omissions among events that existed at that time.
- **AC3.11 — Ubiquitous.** For the purposes of the cursor-vs-filter match required by AC3.5, the `actor` filter shall be compared as the set of distinct ids encoded into the cursor (after the dedup in AC1.11); a replay request whose actor list contains the same set of ids in a different order, or with different multiplicity, shall not be rejected as a mismatch.
- **AC3.12 — Event-driven.** When a multi-actor query is replayed page by page using each returned `next_cursor` until exhaustion, the union of returned events shall equal the set of stored events whose `actor.id` was in the deduplicated filter list (and which satisfied the other filters) at the time the first page was served, with no duplicates and no omissions among events that existed at that time; in particular, no event shall be returned twice merely because its `actor.id` matches more than one id in `<id-list>`.

## Out of scope

- Authorization, authentication, and tenancy/scoping of who may call the endpoint — owned by a separate task.
- Filtering by `action` or `outcome` (only `actor`, `resource`, `from`, `to` are supported in this iteration).
- Multi-value (comma-separated) lists on any parameter other than `actor`. In particular `resource`, `from`, and `to` remain single-valued in this iteration; widening them is a separate decision.
- Free-text search inside `context`.
- Aggregations, counts, or statistics (e.g. total result size, group-by-actor).
- Streaming or push delivery (websockets, SSE); only synchronous paginated reads.
- Export formats other than JSON (no CSV, NDJSON, etc.).
- Retention, archival, or queries against archived (off-live-table) events.
- Rate limiting and quotas.
- Caching (HTTP `ETag`, `If-Modified-Since`) of query responses.
- Backfill strategy for the ULID and structured-actor/resource columns beyond stating that it must occur via Flyway and must not violate append-only on live data — the concrete migration approach belongs in `design.md`.

## Resolved questions

These items were open during the initial spec draft and have since been resolved. They are recorded here for traceability; each carries its resolution and a pointer to the file where the decision is justified.

1. **Backfill semantics for `actor.type` / `resource.type` on existing rows.** Existing events store `actor` and `resource` as flat strings with no enforced type convention.
   *Resolution.* Pre-migration rows are assigned the literal string `"unknown"` for both `actor.type` and `resource.type`. See `design.md` §2.3 (the "Open question resolution" note) for rationale.
2. **Behavior when zero filters are supplied.** All filters are optional and may all be omitted; the endpoint then paginates the entire stream.
   *Resolution.* No hard upper bound (max pages, max wall time) is committed at the requirements level — the same cursor walk applies. Any guard rail is a non-functional / operational concern, not part of this contract. See `design.md` §1.2 ("Query parameters", the "All four data filters may be omitted" rule).
3. **Time-zone handling on input.** Should `from` / `to` accept only UTC `Z`-suffixed RFC 3339, or also numeric offsets?
   *Resolution.* The API accepts any RFC 3339 timestamp with a time-zone designator (`Z` or numeric offset such as `+02:00`) and normalizes to UTC at parse time. AC1.4 / AC1.5 are unaffected because the comparison runs against the server-side UTC `timestamp` column. See `tasks.md` T6 ("`from`/`to` bound as `Instant` (RFC 3339, accepts offsets, normalized to UTC by `Instant`)").
4. **Response envelope shape.** Bare array + cursor header, or wrapped object?
   *Resolution.* The page envelope is `{ "events": [...], "next_cursor": "..." }`. See `design.md` §1.3 ("Success response") for the documented example and AC3.6 for the absent-on-final-page rule.
