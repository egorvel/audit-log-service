-- Creates two roles for the audit-log-service:
--   * audit_migrate - owns and migrates the schema (used by Flyway).
--   * audit_app     - the application connection. Privileges are GRANTed
--                     to it inside the Flyway migration so that it has
--                     SELECT + INSERT on audit_events and nothing else.
-- Both passwords are read from environment variables exposed by docker-compose.

\set audit_migrate_pw `echo "$AUDIT_MIGRATE_PASSWORD"`
\set audit_app_pw `echo "$AUDIT_APP_PASSWORD"`

CREATE ROLE audit_migrate WITH LOGIN PASSWORD :'audit_migrate_pw';
CREATE ROLE audit_app     WITH LOGIN PASSWORD :'audit_app_pw';

GRANT CONNECT ON DATABASE auditlog TO audit_migrate, audit_app;

-- audit_migrate owns the schema and may create / alter objects.
ALTER SCHEMA public OWNER TO audit_migrate;
GRANT USAGE ON SCHEMA public TO audit_app;

-- audit_app gets no default privileges - they are GRANTed explicitly per object
-- inside the Flyway migration that creates the audit_events table.
