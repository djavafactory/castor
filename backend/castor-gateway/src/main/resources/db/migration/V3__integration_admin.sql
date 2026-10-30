-- Integration operations (INGEST-04): replay of quarantined records, the counters of the sources page and the structured
-- subject of a mapping failure. Nothing here changes a row that exists: evidence stays as it was received.

-- A replay is a NEW inbox row that carries the raw payload and the sourceEventId of the record it repeats. replay_of is
-- always the original (never another replay), so every attempt of one record points at the same row.
ALTER TABLE ingest.inbox
    ADD COLUMN replay_of uuid REFERENCES ingest.inbox (reception_id),
    ADD CONSTRAINT inbox_replay_not_self CHECK (replay_of IS NULL OR replay_of <> reception_id);

-- The unique key still recognises a repeat among the records a source sent; a replay is not sent by the source, so it is
-- outside the key and may share the sourceEventId of its original.
ALTER TABLE ingest.inbox DROP CONSTRAINT inbox_port_id_source_source_event_id_key;
CREATE UNIQUE INDEX inbox_source_event_uq ON ingest.inbox (port_id, source, source_event_id) WHERE replay_of IS NULL;

CREATE INDEX inbox_replay_of ON ingest.inbox (replay_of) WHERE replay_of IS NOT NULL;
-- Counters of the sources page: records received in the last 24 hours and the last one
CREATE INDEX inbox_source_received ON ingest.inbox (port_id, source, received_at DESC) WHERE replay_of IS NULL;

-- What a mapping failure was about, so the operator can count unmapped ids without reading messages:
-- subject_type is the source entity type ('BERTH'), subject_id the id the source used. Null for every other reason.
ALTER TABLE ingest.quarantine
    ADD COLUMN subject_type text,
    ADD COLUMN subject_id   text,
    ADD CONSTRAINT quarantine_subject_pair CHECK ((subject_type IS NULL) = (subject_id IS NULL));

CREATE INDEX quarantine_subject ON ingest.quarantine (subject_type, subject_id) WHERE subject_id IS NOT NULL;

-- A repeat is answered "duplicate" and stores nothing, so its count is kept here, per source and minute (received_at of
-- the repeat). Housekeeping deletes minutes older than the outbox retention.
CREATE TABLE ingest.intake_duplicate (
    port_id text        NOT NULL,
    source  text        NOT NULL,
    minute  timestamptz NOT NULL,
    count   bigint      NOT NULL CONSTRAINT intake_duplicate_count CHECK (count > 0),
    PRIMARY KEY (port_id, source, minute)
);
