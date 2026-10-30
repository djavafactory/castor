-- The assets of the current published layout of every port, for the ingest gateway (INGEST-03). The gateway maps the codes
-- a source uses to asset ids with it and has no other right in castor-core's schemas. A view owned by castor_flyway runs
-- with its owner's rights, so the grant below is the gateway's whole access.

CREATE VIEW layout.published_asset AS
SELECT p.code AS port_code,
       a.id   AS asset_id,
       a.code AS code,
       a.type_code AS type_code
FROM ref.port p
         JOIN layout.layout_version v ON v.port_id = p.id AND v.status = 'PUBLISHED'
         JOIN layout.asset_revision r ON r.layout_version_id = v.id
         JOIN layout.asset a ON a.id = r.asset_id;

-- Skipped where castor_gateway does not exist (Dev Services and tests connect as a single superuser)
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'castor_gateway') THEN
        GRANT USAGE ON SCHEMA layout TO castor_gateway;
        GRANT SELECT ON layout.published_asset TO castor_gateway;
    END IF;
END
$$;
