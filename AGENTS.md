## Project map

**Domain:** Audit log service - internal service that receives audit events from other company services and stores them immutably. It is needed for compliance, security, and observability. It is used by compliance officers, SREs, and security analysts.

**Event shape:**
- `timestamp` — set by the server, never by the client
- `actor` — who initiated it (user id / service account), has id and type (`{ "id": "u_42", "type": "user" }`), **required**
- `action` — what happened (`resource.updated`, `user.login`, …)
- `resource` — target object (`{ "id": "9f3b...", "type": "order" }`)
- `outcome` — `success` / `denied` / `error`
- `context` — free-form JSON with details

**Specs:**
- .specs/<feature>/requirements.md - what and why. Contain sections Context, User stories with AC, Out of scope, Open questions.
- .specs/<feature>/design.md - how. API contract, data model, validation rules, invariants, edge cases, integration.
- .specs/<feature>/tasks.md - in what order. Decomposition into safe increments with refs to requirements.md and design.md, explicit and testable DoD, dependencies between tasks. Each task: one safe commit.
- spec is the source of truth: gaps go to spec first, code second.
- acceptance criteria must be written in EARS-style.
- **Always ask 5–7 clarifying questions** before creating or updating any of .specs/<feature>/{requirements,design,tasks}.md. One decision = one question. If there is no real doubt, do not invent one.

## Invariants

- **Append-only.** No `UPDATE`, no `DELETE` on the events table — ever. Not in code, not in migrations, not in ad-hoc scripts. Retention is implemented via archival, not by deleting from the live table.
- **Events are immutable.** Once written, an `AuditEvent` instance is never mutated in memory or in storage.
- **`timestamp` is server-assigned.** The API ignores any client-supplied timestamp.
- **`actor` is required.** Rejected at the API boundary (validation) *and* enforced as `NOT NULL` in the schema. An event without an actor must never reach the repository.
- Before declaring done: compile and run the tests.
- Prefer existing project patterns over new abstractions. Before adding a new abstraction, dependency, folder, framework, or test style search for an existing equivalent in the repo.
- Make the smallest change that correctly solves the task. Do not refactor unrelated code, reformat entire files, rename public APIs, or clean up nearby code unless the task explicitly asks for it.
- Do not make tests pass by weakening assertions, deleting tests, ignoring exceptions, increasing timeouts blindly, or suppressing errors. If a test is wrong, explain why and update it to assert the correct behavior.
- Never print, copy, commit, or expose secrets.
- Create a new branch for a **New task.**

## Architectural rules

**Technology requirements:**
- Java 21, Spring Boot 3, Maven
- PostgreSQL 18.3, Flyway
- Spring Data JPA (Hibernate) for persistence
- Testcontainers

**Persistence conventions:**
- Schema changes go through Flyway migrations (`src/main/resources/db/migration/V{n}__description.sql` or `src/main/java/com/sam/auditlog/db/migration/V{n}__description.java`).
- Migrations are append-only too: never edit a shipped migration — add a new one.
- The app's DB role has `INSERT` + `SELECT` on the events table and nothing else. `UPDATE`/`DELETE` privileges are not granted, not even to the migration role on the events table after it exists.

**Testing conventions:**
- Unit tests – no Spring context.
- Use Mockito for mocks.
- Integration tests with Testcontainers (real Postgres).

**Layout**
- Java configs: : src/main/java/com/sam/auditlog/config
- Controllers: src/main/java/com/sam/auditlog/controller
- Converters: src/main/java/com/sam/auditlog/converter
- DTOs: src/main/java/com/sam/auditlog/dto
- Services: src/main/java/com/sam/auditlog/service
- Repositories: src/main/java/com/sam/auditlog/repository
- Entities: src/main/java/com/sam/auditlog/model
- Specs: .specs/<feature>/{requirements,design,tasks}.md