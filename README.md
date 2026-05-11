# Audit Log Service

Internal audit log service. Other services post audit events here; the service stores them immutably for compliance, security, and observability use cases (compliance officers, SREs, security analysts).

## Event shape

| Field       | Notes                                                                  |
|-------------|------------------------------------------------------------------------|
| `id`        | **Server-assigned** 26-char Crockford-Base32 ULID. Sorts by event time.|
| `timestamp` | **Server-assigned.** Any client-supplied value is ignored.             |
| `actor`     | Required. Object `{ "id", "type" }` — who initiated the event.         |
| `action`    | What happened (`user.login`, `resource.updated`, …).                   |
| `resource`  | Required. Object `{ "id", "type" }` — target of the action.            |
| `outcome`   | One of `SUCCESS`, `DENIED`, `ERROR`.                                   |
| `context`   | Free-form JSON object with additional details.                         |

## Stack

- Java 21, Spring Boot 3.3
- PostgreSQL 18.3
- Flyway for migrations
- Spring Data JPA (Hibernate) for persistence
- JUnit 5 + Testcontainers (real Postgres) for integration tests

## Quick start

### Run with docker-compose

```bash
docker compose up --build
```

The stack brings up:
- `postgres` on `:5432` (image `postgres:18.3-alpine`, with two roles: `audit_migrate` for Flyway and `audit_app` for the app)
- `app` on `:8080`

### Smoke test

```bash
# Append an event
curl -s -X POST http://localhost:8080/api/v1/audit-events \
  -H 'Content-Type: application/json' \
  -d '{
        "actor":    {"id": "u_42",       "type": "user"},
        "action":   "user.login",
        "resource": {"id": "session_abc","type": "session"},
        "outcome":  "SUCCESS",
        "context":  {"ip": "10.0.0.1"}
      }'

# First page (newest first), 10 per page
curl -s 'http://localhost:8080/api/v1/audit-events?limit=10'

# Filter by actor, then follow next_cursor
curl -s 'http://localhost:8080/api/v1/audit-events?actor=u_42&limit=10'
curl -s 'http://localhost:8080/api/v1/audit-events?actor=u_42&limit=10&cursor=<next_cursor>'
```

## REST API

| Method | Path                              | Description                                                                              |
|--------|-----------------------------------|------------------------------------------------------------------------------------------|
| POST   | `/api/v1/audit-events`            | Append a new event. Returns `201 Created` with the saved row.                            |
| GET    | `/api/v1/audit-events`            | List events ordered by `(timestamp DESC, id DESC)` with keyset pagination (see below).   |

### `GET /api/v1/audit-events` parameters

| Name       | Type     | Notes                                                                                       |
|------------|----------|---------------------------------------------------------------------------------------------|
| `actor`    | string   | Exact match on actor id.                                                                    |
| `resource` | string   | Exact match on resource id.                                                                 |
| `from`     | RFC 3339 | Inclusive lower bound on `timestamp`.                                                       |
| `to`       | RFC 3339 | Exclusive upper bound on `timestamp`.                                                       |
| `cursor`   | string   | Opaque cursor from a previous response. Must be replayed with the same filter set.          |
| `limit`    | int      | Page size; must be in `[1, 200]`. Defaults to `50`.                                         |

The response shape is `{ "events": [...], "next_cursor": "..." }`. `next_cursor` is omitted on the final page.

### Error semantics

- `400 Bad Request` — parse-tier failure: malformed JSON body, missing required field, malformed `from`/`to`/`limit`, cursor that is not valid base64url(JSON).
- `422 Unprocessable Entity` — semantic-tier failure on the query endpoint: `from >= to`, `limit` outside `[1, 200]`, blank filter values, cursor whose filter hash does not match the current request, or unsupported cursor schema version.

Both shapes share the envelope `{ "timestamp", "status", "error", "errors": [...] }`.

## API docs (Swagger / OpenAPI)

Once the app is running, the generated docs are available at:

- Swagger UI: <http://localhost:8080/swagger-ui/index.html>
- OpenAPI JSON: <http://localhost:8080/v3/api-docs>

## Building and testing locally

```bash
# Compile and run the full test suite (unit + Testcontainers integration tests)
mvn test

# Build a runnable jar
mvn -DskipTests package
java -jar target/audit-log-service-*.jar
```

The integration tests spin up a real `postgres:18.3-alpine` Testcontainer — Docker must be running.

## Configuration

The app reads its connection details from environment variables, with sensible defaults for local dev (see `src/main/resources/application.yml`):

| Variable        | Purpose                                          | Default                                       |
|-----------------|--------------------------------------------------|-----------------------------------------------|
| `DB_URL`        | App JDBC URL                                     | `jdbc:postgresql://localhost:5432/auditlog`   |
| `DB_USER`       | App DB role (only `SELECT` + `INSERT` granted)   | `audit_app`                                   |
| `DB_PASS`       | App DB password                                  | `audit_app_pw`                                |
| `FLYWAY_URL`    | Flyway JDBC URL                                  | falls back to `DB_URL`                        |
| `FLYWAY_USER`   | Migration role (DDL privileges)                  | `audit_migrate`                               |
| `FLYWAY_PASS`   | Migration password                               | `audit_migrate_pw`                            |

