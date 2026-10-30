-- Live stream (STATE-02): wakes every core instance when a state change is committed, and holds the single-use
-- tickets browsers trade for a WebSocket (they cannot send Basic auth on one).
-- Runs as castor_flyway; the application role gets its rights from the default privileges set in V10.

-- NOTIFY is delivered when the inserting transaction commits, and writers of one port queue on the stream cursor
-- row until then, so notifications leave in sequence order. The payload only says which port moved.
CREATE FUNCTION ops.notify_state_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM pg_notify('ops_state_change', NEW.port_id::text);
    RETURN NULL;
END
$$;

CREATE TRIGGER state_change_notify
    AFTER INSERT ON ops.state_change
    FOR EACH ROW EXECUTE FUNCTION ops.notify_state_change();

-- Only the hash of a ticket is stored; redeeming deletes the row, so a ticket works once, on any instance.
CREATE TABLE ops.stream_ticket
(
    token_hash char(64)    PRIMARY KEY,
    port_id    bigint      NOT NULL REFERENCES ref.port (id),
    username   varchar(128) NOT NULL,
    expires_at timestamptz NOT NULL
);

CREATE INDEX stream_ticket_expiry ON ops.stream_ticket (expires_at);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'castor_app') THEN
        RETURN;
    END IF;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ops.stream_ticket TO castor_app;
END
$$;
