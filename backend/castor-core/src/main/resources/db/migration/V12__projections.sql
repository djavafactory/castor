-- Event projections (STATE-03).
-- Runs as castor_flyway; the application role gets its rights from the default privileges set in V10.

-- A call keeps the sequence of the last event it applied, like equipment does, for the stale-event rule
ALTER TABLE ops.vessel_call
    ADD COLUMN last_source_sequence bigint;

-- A position or an assignment can arrive before any status: the status stays unknown (NULL) until a status event says
-- what it is (R3), instead of being invented
ALTER TABLE ops.equipment_state
    ALTER COLUMN status DROP NOT NULL;

-- A message that never became a valid event (unreadable, or naming a port Castor does not know) has no port to belong to
ALTER TABLE ops.dead_letter
    ALTER COLUMN port_id DROP NOT NULL;
