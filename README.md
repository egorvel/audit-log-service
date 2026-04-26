# Audit Log Service

Internal audit log service. Other services post audit events here; the service stores them immutably for compliance, security, and observability use cases (compliance officers, SREs, security analysts).

## Event shape

| Field       | Notes                                                                  |
|-------------|------------------------------------------------------------------------|
| `timestamp` | **Server-assigned.** Any client-supplied value is ignored.             |
| `actor`     | Required. Who initiated the event (user id / service account).         |
| `action`    | What happened (`user.login`, `resource.updated`, …).                   |
| `resource`  | Target of the action (`project:42`, `invoice:777`).                    |
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
curl -s -X POST http://localhost:8080/api/v1/events \
  -H 'Content-Type: application/json' \
  -d '{
        "actor":    "user:42",
        "action":   "user.login",
        "resource": "session:abc",
        "outcome":  "SUCCESS",
        "context":  {"ip": "10.0.0.1"}
      }'

# Read recent events
curl -s 'http://localhost:8080/api/v1/events?limit=10'
```

## REST API

| Method | Path                              | Description                                                  |
|--------|-----------------------------------|--------------------------------------------------------------|
| POST   | `/api/v1/events`                  | Append a new event. Returns `201 Created` with the saved row.|
| GET    | `/api/v1/events?limit=N`          | Most recent N events (default 100, cap 500).                 |

`POST` returns `400` with a list of field errors on validation failures (e.g. missing `actor`).

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

