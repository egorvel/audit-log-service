-- Append-only audit_events table.
-- Invariants enforced here in addition to application-level checks:
--   * server-assigned timestamp (DEFAULT now())
--   * actor / action / resource NOT NULL
--   * outcome restricted via CHECK
--   * UPDATE / DELETE / TRUNCATE blocked by triggers (defense in depth)
--   * least privilege: app role gets only SELECT + INSERT

CREATE TABLE audit_events (
    id        BIGSERIAL    PRIMARY KEY,
    timestamp TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor     TEXT         NOT NULL,
    action    TEXT         NOT NULL,
    resource  TEXT         NOT NULL,
    outcome   TEXT         NOT NULL CHECK (outcome IN ('success', 'denied', 'error')),
    context   JSONB        NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_audit_events_timestamp ON audit_events (timestamp);
CREATE INDEX idx_audit_events_actor     ON audit_events (actor);
CREATE INDEX idx_audit_events_action    ON audit_events (action);

CREATE OR REPLACE FUNCTION reject_audit_event_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only: % is not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_events_no_update
    BEFORE UPDATE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_audit_event_modification();

CREATE TRIGGER audit_events_no_delete
    BEFORE DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_audit_event_modification();

CREATE TRIGGER audit_events_no_truncate
    BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_audit_event_modification();

-- Grant only the privileges the app role is allowed to hold.
-- Skipped silently if the role does not exist (e.g. in Testcontainers).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'audit_app') THEN
        GRANT SELECT, INSERT ON audit_events TO audit_app;
        GRANT USAGE, SELECT ON SEQUENCE audit_events_id_seq TO audit_app;
    END IF;
END $$;
