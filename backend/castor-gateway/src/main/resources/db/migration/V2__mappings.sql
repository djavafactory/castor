-- Identity and cargo code mappings (INGEST-03). A source never names a Castor asset: a row here says which asset, from
-- when to when. Own Flyway history in schema ingest, owned by castor_gateway.
--
-- The exclusion constraints need btree_gist (equality on text next to a time range). The extension is created by the
-- superuser in db/init/01-roles.sql; the statement below changes nothing where it exists already and creates it where one
-- superuser runs everything (Dev Services, tests).

CREATE EXTENSION IF NOT EXISTS btree_gist SCHEMA public;

-- Physical assets (equipment, storage, berths, gates) and the call refs of second sources. canonical_id is the id of the
-- asset in layout.asset (castor-core), or the vessel call id. Ranges are half-open: [effective_from, effective_to).
CREATE TABLE ingest.identity_mapping (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    port_id            text        NOT NULL
        CONSTRAINT identity_mapping_port_format CHECK (port_id ~ '^[a-z0-9-]{2,40}$'),
    source_system      text        NOT NULL
        CONSTRAINT identity_mapping_source CHECK (source_system IN ('cbas', 'itap', 'scss', 'edic', 'sensors')),
    source_entity_type text        NOT NULL
        CONSTRAINT identity_mapping_type CHECK (source_entity_type IN ('EQUIPMENT', 'STORAGE', 'BERTH', 'GATE', 'VESSEL_CALL')),
    source_id          text        NOT NULL CONSTRAINT identity_mapping_source_id CHECK (source_id <> ''),
    canonical_id       text        NOT NULL CONSTRAINT identity_mapping_canonical_id CHECK (canonical_id <> ''),
    effective_from     timestamptz NOT NULL,
    effective_to       timestamptz,
    created_by         text        NOT NULL,
    created_at         timestamptz NOT NULL,
    CONSTRAINT identity_mapping_range CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT identity_mapping_no_overlap EXCLUDE USING gist (
        port_id WITH =, source_system WITH =, source_entity_type WITH =, source_id WITH =,
        tstzrange(effective_from, effective_to) WITH &&)
);

CREATE INDEX identity_mapping_lookup ON ingest.identity_mapping (port_id, source_system, source_entity_type, source_id);

-- Source family and commodity codes to canonical codes (ref.cargo_family, ref.cargo_type of castor-core). Vocabulary like
-- those tables, so it has no port_id.
CREATE TABLE ingest.cargo_code_mapping (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_system      text        NOT NULL
        CONSTRAINT cargo_code_mapping_source CHECK (source_system IN ('cbas', 'itap', 'scss', 'edic', 'sensors')),
    source_entity_type text        NOT NULL
        CONSTRAINT cargo_code_mapping_type CHECK (source_entity_type IN ('CARGO_FAMILY', 'COMMODITY')),
    source_id          text        NOT NULL CONSTRAINT cargo_code_mapping_source_id CHECK (source_id <> ''),
    canonical_id       text        NOT NULL CONSTRAINT cargo_code_mapping_canonical_id CHECK (canonical_id <> ''),
    effective_from     timestamptz NOT NULL,
    effective_to       timestamptz,
    created_by         text        NOT NULL,
    created_at         timestamptz NOT NULL,
    CONSTRAINT cargo_code_mapping_range CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT cargo_code_mapping_no_overlap EXCLUDE USING gist (
        source_system WITH =, source_entity_type WITH =, source_id WITH =,
        tstzrange(effective_from, effective_to) WITH &&)
);

CREATE INDEX cargo_code_mapping_lookup ON ingest.cargo_code_mapping (source_system, source_entity_type, source_id);

-- The units a cargo family may be measured in (canonical Unit codes). A family without a row is not checked: the core
-- refuses a family it does not know (STATE-04), the gateway only refuses a unit it knows does not fit.
CREATE TABLE ingest.cargo_family_unit (
    family_code text NOT NULL,
    unit_code   text NOT NULL,
    PRIMARY KEY (family_code, unit_code)
);

INSERT INTO ingest.cargo_family_unit (family_code, unit_code) VALUES
    ('DRY_BULK', 'TONNES'),
    ('BREAKBULK', 'TONNES'),
    ('BREAKBULK', 'PIECES'),
    ('LIQUID_BULK', 'CUBIC_METRES'),
    ('LIQUID_BULK', 'TONNES'),
    ('RORO', 'PIECES'),
    ('RORO', 'VEHICLES'),
    ('CONTAINER', 'PIECES');

-- The demo codes: the Westports seed families and cargo types, written the way the synthetic sources send them
INSERT INTO ingest.cargo_code_mapping (source_system, source_entity_type, source_id, canonical_id, effective_from,
                                       created_by, created_at)
SELECT s.code, 'CARGO_FAMILY', f.code, f.code, '1970-01-01T00:00:00Z', 'seed', '1970-01-01T00:00:00Z'
FROM (VALUES ('itap')) AS s (code)
         CROSS JOIN (VALUES ('BREAKBULK'), ('DRY_BULK'), ('LIQUID_BULK'), ('RORO'), ('CONTAINER')) AS f (code);

INSERT INTO ingest.cargo_code_mapping (source_system, source_entity_type, source_id, canonical_id, effective_from,
                                       created_by, created_at)
SELECT s.code, 'COMMODITY', c.code, c.code, '1970-01-01T00:00:00Z', 'seed', '1970-01-01T00:00:00Z'
FROM (VALUES ('itap'), ('sensors')) AS s (code)
         CROSS JOIN (VALUES ('COAL'), ('IRON'), ('GRAIN'), ('FERT'), ('BAUX'), ('AGGR'), ('STEEL'), ('TIMBER'), ('OIL'),
                            ('CHEM'), ('EDOIL'), ('VEH'), ('CONT')) AS c (code);
