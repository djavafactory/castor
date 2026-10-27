-- The audit trail is keyed by the port like every other table (DESIGNER-01). V2 declared port_id as uuid, but
-- ref.port.id is bigint. Nothing writes audit rows before this ticket, so the column is emptied and retyped.
DELETE FROM audit.audit_log;

ALTER TABLE audit.audit_log
    ALTER COLUMN port_id TYPE bigint USING NULL,
    ADD CONSTRAINT audit_log_port_fk FOREIGN KEY (port_id) REFERENCES ref.port (id);

-- Keyset pagination of GET /ports/{code}/audit orders by id within a port.
CREATE INDEX audit_log_port_id_desc ON audit.audit_log (port_id, id DESC);
