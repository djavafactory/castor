-- Layout versions, stable asset identity, asset revisions and typed asset details (CORE-04).
-- Runs as castor_flyway; castor_app gets its rights from the default privileges set in V1.
-- Reference data only lives here. The Westports demo layout is loaded by LayoutSeedJob in local and dev, never by a migration.

CREATE TABLE layout.layout_version
(
    id                       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    port_id                  bigint       NOT NULL REFERENCES ref.port (id),
    version_no               integer      NOT NULL CHECK (version_no >= 1),
    status                   varchar(12)  NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'SUPERSEDED', 'DISCARDED')),
    crs                      varchar(16)  NOT NULL DEFAULT 'EPSG:4326' CHECK (crs = 'EPSG:4326'),
    based_on_version_id      uuid REFERENCES layout.layout_version (id),
    restored_from_version_id uuid REFERENCES layout.layout_version (id),
    note                     text,
    created_by               varchar(64)  NOT NULL,
    created_at               timestamptz  NOT NULL DEFAULT now(),
    reviewed_by              varchar(64),
    published_by             varchar(64),
    published_at             timestamptz,
    row_version              bigint       NOT NULL DEFAULT 0,
    CONSTRAINT layout_version_port_no UNIQUE (port_id, version_no),
    CONSTRAINT layout_version_published_fields CHECK (
        status NOT IN ('PUBLISHED', 'SUPERSEDED') OR (published_at IS NOT NULL AND published_by IS NOT NULL))
);

CREATE UNIQUE INDEX layout_version_one_draft ON layout.layout_version (port_id) WHERE status = 'DRAFT';
CREATE UNIQUE INDEX layout_version_one_published ON layout.layout_version (port_id) WHERE status = 'PUBLISHED';

ALTER TABLE ref.port
    ADD CONSTRAINT port_current_published_layout_fk
        FOREIGN KEY (current_published_layout_id) REFERENCES layout.layout_version (id);

-- Identity only: an asset is never deleted, its id and code are permanent.
CREATE TABLE layout.asset
(
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    port_id    bigint      NOT NULL REFERENCES ref.port (id),
    code       text        NOT NULL CONSTRAINT asset_code_format CHECK (code ~ '^[A-Z0-9][A-Z0-9._/-]{0,39}$'),
    type_code  varchar(40) NOT NULL REFERENCES layout.asset_type (code),
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by varchar(64) NOT NULL,
    CONSTRAINT asset_port_code UNIQUE (port_id, code)
);

CREATE FUNCTION layout.asset_no_delete() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'assets are never deleted' USING ERRCODE = '23514';
END
$$;

CREATE TRIGGER asset_no_delete
    BEFORE DELETE ON layout.asset
    FOR EACH ROW EXECUTE FUNCTION layout.asset_no_delete();

CREATE TABLE layout.asset_revision
(
    asset_id          uuid         NOT NULL REFERENCES layout.asset (id),
    layout_version_id uuid         NOT NULL REFERENCES layout.layout_version (id),
    name              text         NOT NULL,
    geom              geometry(Geometry, 4326) NOT NULL
        CONSTRAINT asset_revision_geom_valid CHECK (ST_IsValid(geom) AND NOT ST_IsEmpty(geom)),
    anchor            geometry(Point, 4326) NOT NULL,
    heading_deg       numeric(7, 3) CONSTRAINT asset_revision_heading CHECK (heading_deg >= 0 AND heading_deg < 360),
    length_m          numeric(10, 3) CHECK (length_m > 0),
    width_m           numeric(10, 3) CHECK (width_m > 0),
    height_m          numeric(10, 3) CHECK (height_m > 0),
    radius_m          numeric(10, 3) CHECK (radius_m > 0),
    line_width_m      numeric(10, 3) CHECK (line_width_m > 0),
    elevation_m       numeric(8, 3) NOT NULL DEFAULT 0,
    clearance_m       numeric(8, 3) NOT NULL DEFAULT 0 CHECK (clearance_m >= 0),
    model_rev         integer CHECK (model_rev >= 1),
    storage_zone_code text,
    operator_name     text,
    description       text,
    accuracy_code     varchar(16)  NOT NULL REFERENCES ref.accuracy_class (code),
    source_code       varchar(32)  NOT NULL REFERENCES ref.data_source (code),
    captured_on       date,
    provenance_note   text,
    override_reason   text,
    override_by       text,
    override_at       timestamptz,
    row_version       bigint       NOT NULL DEFAULT 0,
    created_by        varchar(64)  NOT NULL,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    updated_by        varchar(64)  NOT NULL,
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (asset_id, layout_version_id),
    CONSTRAINT asset_revision_override_together CHECK (
        (override_reason IS NULL AND override_by IS NULL AND override_at IS NULL)
        OR (override_reason IS NOT NULL AND override_by IS NOT NULL AND override_at IS NOT NULL))
);

CREATE INDEX asset_revision_geom_gist ON layout.asset_revision USING gist (geom);
CREATE INDEX asset_revision_version ON layout.asset_revision (layout_version_id);

-- Guard: only a DRAFT version can change, geometry kind matches the asset type, geometry lies inside the port extent.
CREATE FUNCTION layout.asset_revision_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_status varchar(12);
    v_kind   varchar(16);
    v_extent geometry;
    v_geom   text;
BEGIN
    SELECT status INTO v_status
    FROM layout.layout_version
    WHERE id = CASE WHEN TG_OP = 'DELETE' THEN OLD.layout_version_id ELSE NEW.layout_version_id END;
    IF v_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'layout version is not a draft' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.asset_id <> OLD.asset_id OR NEW.layout_version_id <> OLD.layout_version_id) THEN
        RAISE EXCEPTION 'asset id and layout version of a revision cannot change' USING ERRCODE = '23514';
    END IF;

    SELECT t.geometry_kind, p.extent INTO v_kind, v_extent
    FROM layout.asset a
             JOIN layout.asset_type t ON t.code = a.type_code
             JOIN ref.port p ON p.id = a.port_id
    WHERE a.id = NEW.asset_id;

    v_geom := GeometryType(NEW.geom);
    IF v_kind IN ('POINT', 'RECT', 'CIRCLE', 'POLYGON') AND v_geom <> 'POLYGON' THEN
        RAISE EXCEPTION 'geometry of a % asset must be a Polygon, got %', v_kind, v_geom USING ERRCODE = '23514';
    END IF;
    IF v_kind IN ('LINE', 'QUAY_SEGMENT') AND v_geom <> 'LINESTRING' THEN
        RAISE EXCEPTION 'geometry of a % asset must be a LineString, got %', v_kind, v_geom USING ERRCODE = '23514';
    END IF;
    IF NOT ST_CoveredBy(NEW.geom, v_extent) THEN
        RAISE EXCEPTION 'geometry lies outside the port extent' USING ERRCODE = '23514';
    END IF;
    NEW.updated_at := now();
    RETURN NEW;
END
$$;

CREATE TRIGGER asset_revision_guard
    BEFORE INSERT OR UPDATE OR DELETE ON layout.asset_revision
    FOR EACH ROW EXECUTE FUNCTION layout.asset_revision_guard();

-- Detail rows follow the same rule: nothing in a version that is not a draft can change.
CREATE FUNCTION layout.detail_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_status varchar(12);
    v_id     uuid;
BEGIN
    v_id := CASE WHEN TG_OP = 'DELETE' THEN (to_jsonb(OLD) ->> 'layout_version_id')::uuid
                 ELSE (to_jsonb(NEW) ->> 'layout_version_id')::uuid END;
    SELECT status INTO v_status FROM layout.layout_version WHERE id = v_id;
    IF v_status IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'layout version is not a draft' USING ERRCODE = '23514';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$$;

-- Measurements are never stored: computed on the port's measurement CRS.
CREATE VIEW layout.asset_revision_measure AS
SELECT r.asset_id,
       r.layout_version_id,
       CASE WHEN GeometryType(r.geom) = 'POLYGON' THEN ST_Area(ST_Transform(r.geom, p.measure_srid)) END      AS area_m2,
       CASE WHEN GeometryType(r.geom) = 'POLYGON' THEN ST_Perimeter(ST_Transform(r.geom, p.measure_srid)) END AS perimeter_m,
       CASE WHEN GeometryType(r.geom) = 'LINESTRING' THEN ST_Length(ST_Transform(r.geom, p.measure_srid)) END AS length_m
FROM layout.asset_revision r
         JOIN layout.asset a ON a.id = r.asset_id
         JOIN ref.port p ON p.id = a.port_id;

-- ---------------------------------------------------------------------------------------------------------------
-- Typed details, all keyed (asset_id, layout_version_id) and deleted with their revision
-- ---------------------------------------------------------------------------------------------------------------

CREATE TABLE layout.berth_detail
(
    asset_id          uuid NOT NULL,
    layout_version_id uuid NOT NULL,
    depth_m           numeric(6, 2) NOT NULL CHECK (depth_m > 0),
    max_loa_m         numeric(8, 2) CHECK (max_loa_m > 0),
    PRIMARY KEY (asset_id, layout_version_id),
    FOREIGN KEY (asset_id, layout_version_id) REFERENCES layout.asset_revision ON DELETE CASCADE
);

CREATE TABLE layout.berth_cargo_family
(
    asset_id          uuid        NOT NULL,
    layout_version_id uuid        NOT NULL,
    family_code       varchar(16) NOT NULL REFERENCES ref.cargo_family (code),
    PRIMARY KEY (asset_id, layout_version_id, family_code),
    FOREIGN KEY (asset_id, layout_version_id) REFERENCES layout.asset_revision ON DELETE CASCADE
);

CREATE TABLE layout.crane_detail
(
    asset_id            uuid NOT NULL,
    layout_version_id   uuid NOT NULL,
    gauge_m             numeric(8, 2) CHECK (gauge_m > 0),
    outreach_m          numeric(8, 2) CHECK (outreach_m > 0),
    backreach_m         numeric(8, 2) CHECK (backreach_m > 0),
    reach_m             numeric(8, 2) CHECK (reach_m > 0),
    safe_working_load_t numeric(8, 2) CHECK (safe_working_load_t > 0),
    PRIMARY KEY (asset_id, layout_version_id),
    FOREIGN KEY (asset_id, layout_version_id) REFERENCES layout.asset_revision ON DELETE CASCADE
);

CREATE TABLE layout.storage_detail
(
    asset_id               uuid NOT NULL,
    layout_version_id      uuid NOT NULL,
    capacity_value         numeric(14, 3) NOT NULL CHECK (capacity_value >= 0),
    capacity_unit          varchar(8)     NOT NULL CHECK (capacity_unit IN ('t', 'm3', 'pcs', 'veh', 'TEU', 'trucks')),
    usable_ratio           numeric(4, 3)  NOT NULL DEFAULT 1 CHECK (usable_ratio > 0 AND usable_ratio <= 1),
    design_fill            numeric(4, 3)  NOT NULL DEFAULT 0 CHECK (design_fill >= 0 AND design_fill <= 1),
    cargo_type_code        varchar(16) REFERENCES ref.cargo_type (code),
    max_floor_load_t_m2    numeric(8, 2) CHECK (max_floor_load_t_m2 > 0),
    max_stack_m            numeric(6, 2) CHECK (max_stack_m > 0),
    bearing_capacity_kpa   numeric(8, 1) CHECK (bearing_capacity_kpa > 0),
    weather_exposed        boolean NOT NULL DEFAULT true,
    PRIMARY KEY (asset_id, layout_version_id),
    FOREIGN KEY (asset_id, layout_version_id) REFERENCES layout.asset_revision ON DELETE CASCADE
);

CREATE TABLE layout.storage_allowed_cargo
(
    asset_id          uuid        NOT NULL,
    layout_version_id uuid        NOT NULL,
    cargo_type_code   varchar(16) NOT NULL REFERENCES ref.cargo_type (code),
    PRIMARY KEY (asset_id, layout_version_id, cargo_type_code),
    FOREIGN KEY (asset_id, layout_version_id) REFERENCES layout.asset_revision ON DELETE CASCADE
);

CREATE TABLE layout.storage_pad_detail
(
    asset_id          uuid NOT NULL,
    layout_version_id uuid NOT NULL,
    bays              smallint      NOT NULL CHECK (bays >= 1),
    rows              smallint      NOT NULL CHECK (rows >= 1),
    bay_pitch_m       numeric(8, 2) NOT NULL CHECK (bay_pitch_m >= 5),
    row_pitch_m       numeric(8, 2) NOT NULL CHECK (row_pitch_m >= 5),
    max_height_m      numeric(8, 2) NOT NULL CHECK (max_height_m >= 1),
    PRIMARY KEY (asset_id, layout_version_id),
    FOREIGN KEY (asset_id, layout_version_id) REFERENCES layout.asset_revision ON DELETE CASCADE
);

CREATE TABLE layout.container_yard_detail
(
    asset_id          uuid NOT NULL,
    layout_version_id uuid NOT NULL,
    max_tiers         smallint      NOT NULL CHECK (max_tiers BETWEEN 1 AND 9),
    design_fill       numeric(4, 3) NOT NULL CHECK (design_fill >= 0 AND design_fill <= 1),
    capacity_teu      integer       NOT NULL CHECK (capacity_teu >= 0),
    PRIMARY KEY (asset_id, layout_version_id),
    FOREIGN KEY (asset_id, layout_version_id) REFERENCES layout.asset_revision ON DELETE CASCADE
);

CREATE TABLE layout.warehouse_detail
(
    asset_id          uuid NOT NULL,
    layout_version_id uuid NOT NULL,
    roof_rise_m       numeric(6, 2) NOT NULL DEFAULT 0 CHECK (roof_rise_m >= 0),
    dock_doors        smallint      NOT NULL DEFAULT 0 CHECK (dock_doors >= 0),
    PRIMARY KEY (asset_id, layout_version_id),
    FOREIGN KEY (asset_id, layout_version_id) REFERENCES layout.asset_revision ON DELETE CASCADE
);

CREATE TABLE layout.flow_detail
(
    asset_id          uuid NOT NULL,
    layout_version_id uuid NOT NULL,
    rate_value        numeric(12, 2) NOT NULL CHECK (rate_value > 0),
    rate_unit         varchar(8)     NOT NULL CHECK (rate_unit IN ('t/h', 'm3/h')),
    PRIMARY KEY (asset_id, layout_version_id),
    FOREIGN KEY (asset_id, layout_version_id) REFERENCES layout.asset_revision ON DELETE CASCADE
);

CREATE TABLE layout.surface_detail
(
    asset_id          uuid        NOT NULL,
    layout_version_id uuid        NOT NULL,
    surface_code      varchar(16) NOT NULL REFERENCES ref.surface (code),
    PRIMARY KEY (asset_id, layout_version_id),
    FOREIGN KEY (asset_id, layout_version_id) REFERENCES layout.asset_revision ON DELETE CASCADE
);

CREATE TABLE layout.zone_allowed_type
(
    asset_id          uuid        NOT NULL,
    layout_version_id uuid        NOT NULL,
    allowed_type_code varchar(40) NOT NULL REFERENCES layout.asset_type (code),
    PRIMARY KEY (asset_id, layout_version_id, allowed_type_code),
    FOREIGN KEY (asset_id, layout_version_id) REFERENCES layout.asset_revision ON DELETE CASCADE
);

CREATE TABLE layout.mobile_detail
(
    asset_id          uuid NOT NULL,
    layout_version_id uuid NOT NULL,
    position_mode     varchar(8)    NOT NULL DEFAULT 'PARKED' CHECK (position_mode IN ('FIXED', 'PARKED')),
    uncertainty_m     numeric(6, 2) NOT NULL DEFAULT 5 CHECK (uncertainty_m >= 0),
    PRIMARY KEY (asset_id, layout_version_id),
    FOREIGN KEY (asset_id, layout_version_id) REFERENCES layout.asset_revision ON DELETE CASCADE
);

CREATE TABLE layout.equipment_detail
(
    asset_id           uuid NOT NULL,
    layout_version_id  uuid NOT NULL,
    model              text,
    operator           text,
    rated_throughput   numeric(12, 2) CHECK (rated_throughput > 0),
    throughput_unit    varchar(8) CHECK (throughput_unit IN ('t/h', 'lifts/h', 'm3/h', 'veh/h')),
    design_status      varchar(12)    NOT NULL DEFAULT 'ACTIVE'
        CHECK (design_status IN ('ACTIVE', 'IDLE', 'OFFLINE', 'MAINTENANCE')),
    PRIMARY KEY (asset_id, layout_version_id),
    FOREIGN KEY (asset_id, layout_version_id) REFERENCES layout.asset_revision ON DELETE CASCADE,
    CONSTRAINT equipment_throughput_pair CHECK ((rated_throughput IS NULL) = (throughput_unit IS NULL))
);

CREATE TABLE layout.asset_link
(
    layout_version_id uuid        NOT NULL,
    from_asset_id     uuid        NOT NULL,
    to_asset_id       uuid        NOT NULL,
    kind              varchar(12) NOT NULL CHECK (kind IN ('SERVES', 'FEEDS', 'BELONGS_TO', 'CONNECTS_TO')),
    PRIMARY KEY (layout_version_id, from_asset_id, to_asset_id, kind),
    CONSTRAINT asset_link_not_self CHECK (from_asset_id <> to_asset_id),
    FOREIGN KEY (from_asset_id, layout_version_id) REFERENCES layout.asset_revision ON DELETE CASCADE,
    FOREIGN KEY (to_asset_id, layout_version_id) REFERENCES layout.asset_revision ON DELETE CASCADE
);

CREATE INDEX asset_link_to ON layout.asset_link (layout_version_id, to_asset_id);

DO
$$
    DECLARE
        t text;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['berth_detail','berth_cargo_family','crane_detail','storage_detail',
            'storage_allowed_cargo','storage_pad_detail','container_yard_detail','warehouse_detail','flow_detail',
            'surface_detail','zone_allowed_type','mobile_detail','equipment_detail','asset_link']
            LOOP
                EXECUTE format('CREATE TRIGGER %I BEFORE INSERT OR UPDATE OR DELETE ON layout.%I '
                                   'FOR EACH ROW EXECUTE FUNCTION layout.detail_guard()', t || '_guard', t);
            END LOOP;
    END
$$;

-- ---------------------------------------------------------------------------------------------------------------
-- Copy: used by draft creation and restore. Asset ids stay identical; revisions and every detail row are copied.
-- ---------------------------------------------------------------------------------------------------------------

CREATE FUNCTION layout.copy_revisions(p_from uuid, p_to uuid) RETURNS integer
    LANGUAGE plpgsql AS
$$
DECLARE
    v_count integer;
BEGIN
    INSERT INTO layout.asset_revision (asset_id, layout_version_id, name, geom, anchor, heading_deg, length_m,
                                       width_m, height_m, radius_m, line_width_m, elevation_m, clearance_m, model_rev,
                                       storage_zone_code, operator_name, description, accuracy_code, source_code,
                                       captured_on, provenance_note, override_reason, override_by, override_at,
                                       created_by, created_at, updated_by)
    SELECT asset_id, p_to, name, geom, anchor, heading_deg, length_m, width_m, height_m, radius_m, line_width_m,
           elevation_m, clearance_m, model_rev, storage_zone_code, operator_name, description, accuracy_code,
           source_code, captured_on, provenance_note, override_reason, override_by, override_at, created_by,
           created_at, updated_by
    FROM layout.asset_revision
    WHERE layout_version_id = p_from;
    GET DIAGNOSTICS v_count = ROW_COUNT;

    INSERT INTO layout.berth_detail SELECT asset_id, p_to, depth_m, max_loa_m
                                    FROM layout.berth_detail WHERE layout_version_id = p_from;
    INSERT INTO layout.berth_cargo_family SELECT asset_id, p_to, family_code
                                          FROM layout.berth_cargo_family WHERE layout_version_id = p_from;
    INSERT INTO layout.crane_detail SELECT asset_id, p_to, gauge_m, outreach_m, backreach_m, reach_m, safe_working_load_t
                                    FROM layout.crane_detail WHERE layout_version_id = p_from;
    INSERT INTO layout.storage_detail SELECT asset_id, p_to, capacity_value, capacity_unit, usable_ratio, design_fill,
                                             cargo_type_code, max_floor_load_t_m2, max_stack_m, bearing_capacity_kpa,
                                             weather_exposed
                                      FROM layout.storage_detail WHERE layout_version_id = p_from;
    INSERT INTO layout.storage_allowed_cargo SELECT asset_id, p_to, cargo_type_code
                                             FROM layout.storage_allowed_cargo WHERE layout_version_id = p_from;
    INSERT INTO layout.storage_pad_detail SELECT asset_id, p_to, bays, rows, bay_pitch_m, row_pitch_m, max_height_m
                                          FROM layout.storage_pad_detail WHERE layout_version_id = p_from;
    INSERT INTO layout.container_yard_detail SELECT asset_id, p_to, max_tiers, design_fill, capacity_teu
                                             FROM layout.container_yard_detail WHERE layout_version_id = p_from;
    INSERT INTO layout.warehouse_detail SELECT asset_id, p_to, roof_rise_m, dock_doors
                                        FROM layout.warehouse_detail WHERE layout_version_id = p_from;
    INSERT INTO layout.flow_detail SELECT asset_id, p_to, rate_value, rate_unit
                                   FROM layout.flow_detail WHERE layout_version_id = p_from;
    INSERT INTO layout.surface_detail SELECT asset_id, p_to, surface_code
                                      FROM layout.surface_detail WHERE layout_version_id = p_from;
    INSERT INTO layout.zone_allowed_type SELECT asset_id, p_to, allowed_type_code
                                         FROM layout.zone_allowed_type WHERE layout_version_id = p_from;
    INSERT INTO layout.mobile_detail SELECT asset_id, p_to, position_mode, uncertainty_m
                                     FROM layout.mobile_detail WHERE layout_version_id = p_from;
    INSERT INTO layout.equipment_detail SELECT asset_id, p_to, model, operator, rated_throughput, throughput_unit,
                                               design_status
                                        FROM layout.equipment_detail WHERE layout_version_id = p_from;
    INSERT INTO layout.asset_link SELECT p_to, from_asset_id, to_asset_id, kind
                                  FROM layout.asset_link WHERE layout_version_id = p_from;
    RETURN v_count;
END
$$;
