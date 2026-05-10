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
