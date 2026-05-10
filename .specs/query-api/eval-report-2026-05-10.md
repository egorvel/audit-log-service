# Specs self-evaluation — query-api

Date: 2026-05-10
Evaluated: `.specs/query-api/{requirements,design,tasks}.md` (state at HEAD of `feature/query-audit-events`).

| # | Check | Verdict |
| - | ----- | ------- |
| 1 | Each AC is testable | WEAK |
| 2 | Tasks have refs and DoD | PASS |
| 3 | Dependencies between tasks are explicit | PASS |
| 4 | Every AC in `requirements.md` is addressed by at least one section in `design.md` | PASS |
| 5 | Every AC traces to at least one task whose DoD would fail if the AC regressed | PASS |
| 6 | No contradictions between `requirements.md`, `design.md`, and `tasks.md` | PASS |
| 7 | Cross-references resolve | **FAIL** |
| 8 | Every AC is in EARS form | PASS |

---

## 1. Each AC is testable — **WEAK**

26 of 27 ACs are directly testable via HTTP request → response assertions or DB-state comparison. The exception is **AC3.2**: *"The cursor shall be opaque to clients: clients shall not be required, expected, or able to interpret its contents."* The "not able to interpret" half is a claim about client behavior that is not observable from a server-side test. The "not required, not expected" parts can be exercised by walking pages without parsing the cursor. Tightening AC3.2 to a server-observable form (e.g. "the cursor's contents are not part of the public contract; the server may change them at any time, signaled by `v`") would lift this to PASS.

## 2. Tasks have refs and DoD — **PASS**

Every task (T1–T7) carries an explicit `**Refs.**` block (requirements ACs + design §s) and a `**DoD.**` block with verifiable bullets (e.g. *"`mvn verify` green"*, *"new integration test asserts row count preserved; every row has 26-char ULID `id`; …"*).

## 3. Dependencies between tasks are explicit — **PASS**

`tasks.md` opens with a Mermaid-style graph and a textual gloss: *"T2 and T3 are independent and can land in either order; everything from T4 onward depends on T2; T5 depends on both T3 and T4."*

## 4. Every AC in `requirements.md` is addressed by at least one section in `design.md` — **PASS**

Sample trace:

| AC | Design section |
| -- | -------------- |
| AC1.1 | §1.1 (resource path) |
| AC1.2–1.6 | §1.2 (query params), §6.3 (repo query) |
| AC1.7 | §1.3 (success response) |
| AC1.8–1.9 | §1.4 (400 vs 422 mapping), §5 (validation) |
| AC1.10 | §6.3 (read path uses SELECT only) |
| AC2.1–2.2 | §3.1 (sort & determinism) |
| AC2.3 | §3 (pagination) |
| AC2.4 | §1.3 (RFC 3339, `Z`-suffixed) |
| AC3.1–3.4 | §3.2 (keyset), §3.3 (cursor format) |
| AC3.5 | §3.3 (filter hash) |
| AC3.6 | §3.4 (has-more detection) |
| AC3.7–3.8 | §1.2 (limit), §5.2 (semantic checks) |
| AC3.9–3.10 | §1.4, §3.1, §3.2 |

No orphan ACs.

## 5. Every AC traces to at least one task whose DoD would fail if the AC regressed — **PASS**

Spot-checks: AC1.10 → T6 *"verify the DB rows are byte-identical before and after the GET"*; AC2.1/AC2.2 → T4 *"same-millisecond tiebreaker"* + T6 *"ordering & ties"*; AC3.4 → T6 *"insert M new events between page 1 and page 2, assert page 2 still pulls from the original snapshot"*; AC3.10 → T6 *"walk to exhaustion via `next_cursor`, assert union equals seeded set, no duplicates, no omissions"*. Every AC has an analogous test bullet in T1–T7's DoD.

## 6. No contradictions between `requirements.md`, `design.md`, and `tasks.md` — **PASS**

The three previously-known mismatches were resolved before this evaluation: migration wording in §Context now matches design §2.3 (table replacement); AC3.5 is tightened to match the filter-hash check in design §3.3; the design example timestamp shows microsecond precision (`.123456Z`), consistent with AC2.4's "no rounding" and tasks.md T6's pass-through assertion. ULID library version is now pinned in tasks.md T1 (`ulid-creator:5.2.3`) — no remaining version drift.

## 7. Cross-references resolve — **FAIL**

`tasks.md` T3 refs read: *"design §1.4 (error mapping), **§3.3** (cursor format), **§3.5 (filter hash)**, §5 (validation), §6.1 (handler), §6.2 (`CursorCodec`)"*. **`design.md` has no §3.5** — its §3 stops at §3.4 (Has-more detection). The filter-hash content is in §3.3 (Cursor format), which is already cited on the same line.

Suggested fix: drop `§3.5 (filter hash)` from T3's `Refs.` line — §3.3 already covers it. One-character delete in a single line.

All other cross-references checked resolve: every other `§X.Y`, `AC…`, and `AGENTS.md §…` in tasks.md and design.md points to a real section.

## 8. Every AC is in EARS form — **PASS**

Every AC carries an explicit EARS label (Ubiquitous / Event-driven / Unwanted) and the prose matches the label's pattern:

- Ubiquitous: *"The system shall …"* — AC1.1, 1.6, 1.7, 1.10, 2.1, 2.2, 2.4, 3.1, 3.2, 3.4, 3.6, 3.7.
- Event-driven: *"When …, the system shall …"* — AC1.2–1.5, 2.3, 3.3, 3.5, 3.10.
- Unwanted: *"If …, then the system shall …"* — AC1.8, 1.9, 3.8a, 3.8b, 3.9a, 3.9b.

No State-driven or Optional ACs are present, which is correct — none are needed for this feature.

Minor note (does not affect verdict): AC3.7 bundles three statements (limit is optional, default is 50, max is 200). Each one is in Ubiquitous form, but splitting into three atomic ACs would make individual regressions easier to attribute. Atomicity is a separate quality dimension from EARS form.

---

## Summary

One blocking issue: the broken `§3.5` reference in `tasks.md` T3. One AC (AC3.2) makes a claim about clients that isn't server-observable; everything else is clean.
