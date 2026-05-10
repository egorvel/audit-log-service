# Specs self-evaluation checklist

Verify specs using the following criteria:

- Each AC is testable.
- Tasks have refs and DoD.
- Dependencies between tasks are explicit.
- Every AC in requirements.md is addressed by at least one section in design.md.
- Every AC traces to at least one task whose DoD would fail if the AC regressed.
- No contradictions between requirements.md, design.md, and tasks.md (same fact, same answer everywhere).
- Cross-references resolve: every §X.Y, AC…, or "see Section …" points to a real section that says what's claimed.
- Every AC is in EARS form (Ubiquitous / Event-driven / Unwanted / State-driven / Optional).

For each item, emit PASS / FAIL / WEAK with one-line evidence. Save the report to .specs/<feature>/eval-report-<date>.md