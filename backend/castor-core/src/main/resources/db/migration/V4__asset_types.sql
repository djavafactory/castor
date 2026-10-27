-- Asset type registry (CORE-02): one row per placeable type. Adding a type is a migration like this one plus a 3D model.

CREATE TABLE layout.asset_type (
    code                  varchar(40) PRIMARY KEY
        CONSTRAINT asset_type_code_format CHECK (code ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    label                 varchar(80)  NOT NULL,
    palette_group_code    varchar(16)  NOT NULL REFERENCES ref.palette_group (code),
    category_code         varchar(16)  NOT NULL REFERENCES ref.asset_category (code),
    geometry_kind         varchar(16)  NOT NULL
        CHECK (geometry_kind IN ('POINT', 'RECT', 'CIRCLE', 'LINE', 'POLYGON', 'QUAY_SEGMENT')),
    placement_mode        varchar(8)   NOT NULL CHECK (placement_mode IN ('PLACE', 'DRAW')),
    is_mobile             boolean      NOT NULL DEFAULT false,
    is_solid              boolean      NOT NULL DEFAULT false,
    is_flat               boolean      NOT NULL DEFAULT false,
    is_zone               boolean      NOT NULL DEFAULT false,
    may_extend_over_water boolean      NOT NULL DEFAULT false,
    must_be_on_berth      boolean      NOT NULL DEFAULT false,
    default_length_m      numeric(8, 2),
    default_width_m       numeric(8, 2),
    default_height_m      numeric(8, 2),
    default_radius_m      numeric(8, 2),
    default_line_width_m  numeric(8, 2),
    min_length_m          numeric(8, 2) NOT NULL DEFAULT 0.5 CHECK (min_length_m > 0),
    min_width_m           numeric(8, 2) NOT NULL DEFAULT 0.5 CHECK (min_width_m > 0),
    min_height_m          numeric(8, 2) NOT NULL DEFAULT 0.1 CHECK (min_height_m > 0),
    min_radius_m          numeric(8, 2) NOT NULL DEFAULT 1 CHECK (min_radius_m > 0),
    max_length_m          numeric(8, 2),
    max_width_m           numeric(8, 2),
    max_height_m          numeric(8, 2),
    max_radius_m          numeric(8, 2),
    default_capacity_value numeric(12, 2) CHECK (default_capacity_value > 0),
    default_capacity_unit varchar(16),
    -- Type specific defaults that have no column of their own (reach, outreach, tiers, rate, bay count ...)
    params                jsonb        NOT NULL DEFAULT '{}',
    model_key             varchar(60)  NOT NULL,
    model_rev             integer      NOT NULL DEFAULT 1 CHECK (model_rev >= 1),
    colour_hex            char(7)      NOT NULL CHECK (colour_hex ~ '^#[0-9A-Fa-f]{6}$'),
    hint                  text         NOT NULL,
    sort_order            smallint     NOT NULL,
    CONSTRAINT asset_type_capacity_pair CHECK ((default_capacity_value IS NULL) = (default_capacity_unit IS NULL)),
    CONSTRAINT asset_type_flat_not_solid CHECK (NOT (is_flat AND is_solid)),
    CONSTRAINT asset_type_draw_geometry CHECK (placement_mode = 'PLACE' OR geometry_kind IN ('LINE', 'POLYGON')),
    CONSTRAINT asset_type_default_length_min CHECK (default_length_m IS NULL OR default_length_m >= min_length_m),
    CONSTRAINT asset_type_default_width_min CHECK (default_width_m IS NULL OR default_width_m >= min_width_m),
    CONSTRAINT asset_type_default_height_min CHECK (default_height_m IS NULL OR default_height_m >= min_height_m),
    CONSTRAINT asset_type_default_radius_min CHECK (default_radius_m IS NULL OR default_radius_m >= min_radius_m),
    CONSTRAINT asset_type_max_length CHECK (max_length_m IS NULL OR max_length_m >= min_length_m),
    CONSTRAINT asset_type_max_width CHECK (max_width_m IS NULL OR max_width_m >= min_width_m),
    CONSTRAINT asset_type_max_height CHECK (max_height_m IS NULL OR max_height_m >= min_height_m),
    CONSTRAINT asset_type_max_radius CHECK (max_radius_m IS NULL OR max_radius_m >= min_radius_m)
);

CREATE INDEX asset_type_palette_group ON layout.asset_type (palette_group_code, sort_order);

-- Flags: S solid, F flat, Z zone, M mobile, W may extend over water, B must be on berth.
-- Sizes: length, width, height (POINT, RECT), radius, line width (LINE). The hint is filled below.
INSERT INTO layout.asset_type (code, label, palette_group_code, category_code, geometry_kind, placement_mode,
                               is_mobile, is_solid, is_flat, is_zone, may_extend_over_water, must_be_on_berth,
                               default_length_m, default_width_m, default_height_m, default_radius_m,
                               default_line_width_m, default_capacity_value, default_capacity_unit, params,
                               model_key, colour_hex, hint, sort_order)
SELECT t.code, t.label, t.grp, t.cat, t.geom, t.mode,
       position('M' in t.flags) > 0, position('S' in t.flags) > 0, position('F' in t.flags) > 0,
       position('Z' in t.flags) > 0, position('W' in t.flags) > 0, position('B' in t.flags) > 0,
       t.l, t.w, t.h, t.r, t.lw, t.cap, t.cap_unit, coalesce(t.params, '{}')::jsonb,
       lower(t.code), t.colour,
       CASE
           WHEN t.mode = 'PLACE' THEN 'Click the map to place a ' || lower(t.label) || '.'
           WHEN t.geom = 'POLYGON' THEN 'Click the corners, then double-click or press Enter.'
           ELSE 'Click along the path, then double-click or press Enter.'
       END,
       (row_number() OVER ())::smallint
FROM (VALUES
    ('MOBILE_HARBOUR_CRANE', 'Mobile harbour crane', 'CRANES', 'LIFTING', 'POINT', 'PLACE', 'M', 18, 9, 28, NULL, NULL, NULL, NULL, '{"reachM":32}', '#F2622E'),
    ('LEVEL_LUFFING_CRANE', 'Level luffing crane', 'CRANES', 'LIFTING', 'POINT', 'PLACE', 'M', 24, 10, 40, NULL, NULL, NULL, NULL, '{"reachM":40}', '#E8A317'),
    ('CRAWLER_CRANE', 'Crawler crane', 'CRANES', 'LIFTING', 'POINT', 'PLACE', 'M', 12, 8, 35, NULL, NULL, NULL, NULL, '{"reachM":35}', '#E8A317'),
    ('HARBOUR_GANTRY_CRANE', 'Harbour gantry crane', 'CRANES', 'LIFTING', 'POINT', 'PLACE', 'S', 24, 20, 32, NULL, NULL, NULL, NULL, '{"reachM":24}', '#3C6FB4'),
    ('SHIP_UNLOADER', 'Ship unloader', 'CRANES', 'DRY_BULK', 'POINT', 'PLACE', 'S', 26, 18, 28, NULL, NULL, NULL, NULL, '{"reachM":30}', '#3C6FB4'),
    ('QUAY_CRANE_RAIL', 'Quay crane (rail)', 'CRANES', 'LIFTING', 'POINT', 'PLACE', 'B', NULL, 16, 52, NULL, NULL, NULL, NULL, '{"gaugeM":16,"outreachM":65,"backreachM":32}', '#F2622E'),
    ('STS_CRANE', 'Ship-to-shore crane', 'CRANES', 'CONTAINER', 'POINT', 'PLACE', '', 16, 22, 55, NULL, NULL, NULL, NULL, '{"outreachM":65,"backreachM":30}', '#F2622E'),
    ('RTG', 'Rubber-tyred gantry', 'CRANES', 'CONTAINER', 'POINT', 'PLACE', '', 12, 24, 18, NULL, NULL, NULL, NULL, NULL, '#E8A317'),
    ('FORKLIFT', 'Forklift', 'HANDLING', 'MOBILE', 'POINT', 'PLACE', 'M', 4.2, 2.2, 2.6, NULL, NULL, NULL, NULL, NULL, '#E8A317'),
    ('REACH_STACKER', 'Reach stacker', 'HANDLING', 'MOBILE', 'POINT', 'PLACE', 'M', 9.5, 4, 4.5, NULL, NULL, NULL, NULL, NULL, '#4F6D8F'),
    ('WHEEL_LOADER', 'Wheel loader', 'HANDLING', 'MOBILE', 'POINT', 'PLACE', 'M', 9, 3.4, 3.6, NULL, NULL, NULL, NULL, NULL, '#E8A317'),
    ('TERMINAL_TRACTOR', 'Terminal tractor', 'HANDLING', 'MOBILE', 'POINT', 'PLACE', 'M', 6, 2.5, 3.2, NULL, NULL, NULL, NULL, NULL, '#F2622E'),
    ('EXCAVATOR', 'Excavator', 'HANDLING', 'MOBILE', 'POINT', 'PLACE', 'M', 10, 3.2, 3.4, NULL, NULL, NULL, NULL, NULL, '#E8A317'),
    ('MOBILE_CONVEYOR_LOADER', 'Mobile conveyor loader', 'HANDLING', 'DRY_BULK', 'POINT', 'PLACE', 'M', 30, 4, 8, NULL, NULL, NULL, NULL, '{"rateTph":800}', '#4F6D8F'),
    ('WAREHOUSE_POLY', 'Warehouse (polygon)', 'STORAGE', 'STORAGE', 'POLYGON', 'DRAW', 'S', NULL, NULL, 9, NULL, NULL, NULL, NULL, '{"usableFraction":0.75}', '#C9BFA3'),
    ('WAREHOUSE', 'Warehouse', 'STORAGE', 'STORAGE', 'RECT', 'PLACE', 'S', 80, 40, 12, NULL, NULL, NULL, NULL, '{"roofRiseM":3,"dockDoors":6}', '#C9BFA3'),
    ('TRANSIT_SHED', 'Transit shed', 'STORAGE', 'STORAGE', 'RECT', 'PLACE', 'S', 60, 30, 8, NULL, NULL, 900, 't', NULL, '#C9BFA3'),
    ('COVERED_BAY', 'Covered bay', 'STORAGE', 'STORAGE', 'RECT', 'PLACE', 'S', 36, 20, 7, NULL, NULL, 500, 't', NULL, '#B9AE8E'),
    ('LAYDOWN_YARD', 'Laydown yard', 'STORAGE', 'STORAGE', 'POLYGON', 'DRAW', 'F', NULL, NULL, NULL, NULL, NULL, 400, 'pcs', '{"surface":"CONCRETE"}', '#D8CFB8'),
    ('QUAY_STAGING', 'Quay staging area', 'STORAGE', 'STORAGE', 'RECT', 'PLACE', 'F', 70, 30, NULL, NULL, NULL, 300, 'pcs', NULL, '#E0C98F'),
    ('STORAGE_PAD', 'Storage pad', 'STORAGE', 'DRY_BULK', 'RECT', 'PLACE', 'S', 124, 34, NULL, NULL, NULL, NULL, NULL, '{"bays":2,"rows":1,"pitchLengthM":62,"pitchWidthM":34,"maxHeightM":10}', '#E3DCCD'),
    ('SILO', 'Silo', 'STORAGE', 'DRY_BULK', 'CIRCLE', 'PLACE', 'S', NULL, NULL, 26, 9, NULL, NULL, NULL, NULL, '#C9CDD2'),
    ('STOCKPILE_BAY', 'Stockpile bay', 'STORAGE', 'DRY_BULK', 'RECT', 'PLACE', 'S', 40, 24, 9, NULL, NULL, 6000, 't', NULL, '#8C7A5A'),
    ('HOPPER', 'Hopper', 'STORAGE', 'DRY_BULK', 'RECT', 'PLACE', 'S', 14, 12, 9, NULL, NULL, 120, 't', NULL, '#E8A317'),
    ('TRANSFER_TOWER', 'Transfer tower', 'STORAGE', 'DRY_BULK', 'RECT', 'PLACE', 'S', 8, 8, 24, NULL, NULL, NULL, NULL, NULL, '#9AA3AD'),
    ('LIQUID_TANK', 'Liquid tank', 'STORAGE', 'LIQUID_BULK', 'CIRCLE', 'PLACE', 'S', NULL, NULL, 15, 13, NULL, NULL, NULL, '{"bund":true}', '#B9C4CC'),
    ('BUND_WALL', 'Bund wall', 'STORAGE', 'LIQUID_BULK', 'POLYGON', 'DRAW', 'F', NULL, NULL, 1.2, NULL, NULL, NULL, NULL, NULL, '#9A9F98'),
    ('PUMP_STATION', 'Pump station', 'STORAGE', 'LIQUID_BULK', 'RECT', 'PLACE', 'S', 9, 6, 4, NULL, NULL, NULL, NULL, NULL, '#5E7C99'),
    ('BUILDING', 'Building', 'STORAGE', 'STORAGE', 'RECT', 'PLACE', 'S', 30, 12, 6, NULL, NULL, NULL, NULL, NULL, '#9AA3AD'),
    ('CONTAINER_YARD', 'Container yard', 'STORAGE', 'CONTAINER', 'RECT', 'PLACE', 'S', 150, 46, 13, NULL, NULL, 3000, 'TEU', '{"tiers":5}', '#7C8794'),
    ('FLATBED_TRUCK', 'Flatbed truck', 'TRANSPORT', 'MOBILE', 'POINT', 'PLACE', 'M', 12, 2.5, 3.5, NULL, NULL, NULL, NULL, NULL, '#4F6D8F'),
    ('LOW_BED_TRAILER', 'Low-bed trailer', 'TRANSPORT', 'MOBILE', 'POINT', 'PLACE', 'M', 16, 3, 3.4, NULL, NULL, NULL, NULL, NULL, '#F2622E'),
    ('DUMP_TRUCK', 'Dump truck', 'TRANSPORT', 'MOBILE', 'POINT', 'PLACE', 'M', 9, 2.6, 3.4, NULL, NULL, NULL, NULL, NULL, '#E8A317'),
    ('TANK_TRUCK', 'Tank truck', 'TRANSPORT', 'MOBILE', 'POINT', 'PLACE', 'M', 11, 2.6, 3.4, NULL, NULL, NULL, NULL, NULL, '#C9CDD2'),
    ('RORO_TRANSPORTER', 'RoRo transporter', 'TRANSPORT', 'MOBILE', 'POINT', 'PLACE', 'M', 18, 2.6, 4.2, NULL, NULL, NULL, NULL, NULL, '#2B3442'),
    ('BERTH', 'Berth', 'MARINE', 'MARINE', 'QUAY_SEGMENT', 'PLACE', 'W', 200, NULL, NULL, NULL, NULL, NULL, NULL, '{"depthM":14}', '#E8A317'),
    ('CONVEYOR', 'Conveyor', 'MARINE', 'DRY_BULK', 'LINE', 'DRAW', '', NULL, NULL, 7, NULL, 2.4, NULL, NULL, '{"rateTph":700}', '#4F6D8F'),
    ('LOADING_ARM', 'Loading arm', 'MARINE', 'LIQUID_BULK', 'POINT', 'PLACE', 'SW', 8, 4, 10, NULL, NULL, NULL, NULL, NULL, '#C8423B'),
    ('FENDER', 'Fender', 'MARINE', 'MARINE', 'LINE', 'DRAW', 'W', NULL, NULL, 1.2, NULL, 1.1, NULL, NULL, '{"blockSpacingM":6}', '#2B3442'),
    ('BOLLARD', 'Bollard', 'MARINE', 'MARINE', 'POINT', 'PLACE', 'W', 1.2, 1.2, 0.9, NULL, NULL, NULL, NULL, NULL, '#2B3442'),
    ('RORO_RAMP', 'RoRo ramp', 'MARINE', 'RORO', 'RECT', 'PLACE', 'SW', 24, 10, 3, NULL, NULL, NULL, NULL, NULL, '#8C949E'),
    ('PIPELINE', 'Pipeline', 'MARINE', 'LIQUID_BULK', 'LINE', 'DRAW', '', NULL, NULL, 1.4, NULL, 0.9, NULL, NULL, NULL, '#C8423B'),
    ('PIPE_RACK', 'Pipe rack', 'MARINE', 'LIQUID_BULK', 'LINE', 'DRAW', '', NULL, NULL, 5, NULL, 2.4, NULL, NULL, NULL, '#6B7480'),
    ('ROAD_SEGMENT', 'Road segment', 'ROADS', 'TRANSPORT', 'RECT', 'PLACE', 'F', 200, 12, NULL, NULL, NULL, NULL, NULL, NULL, '#C9C2B3'),
    ('INTERNAL_ROAD', 'Internal road', 'ROADS', 'TRANSPORT', 'LINE', 'DRAW', 'F', NULL, NULL, NULL, NULL, 12, NULL, NULL, NULL, '#B9B2A3'),
    ('JUNCTION', 'Junction', 'ROADS', 'TRANSPORT', 'RECT', 'PLACE', 'F', 24, 24, NULL, NULL, NULL, NULL, NULL, NULL, '#B9B2A3'),
    ('GATE', 'Gate', 'ROADS', 'TRANSPORT', 'RECT', 'PLACE', 'S', 14, 8, 4, NULL, NULL, NULL, NULL, NULL, '#B49A6A'),
    ('WEIGHBRIDGE', 'Weighbridge', 'ROADS', 'TRANSPORT', 'RECT', 'PLACE', 'F', 20, 4, 0.3, NULL, NULL, NULL, NULL, NULL, '#7C8794'),
    ('TRUCK_HOLDING', 'Truck holding area', 'ROADS', 'TRANSPORT', 'RECT', 'PLACE', 'F', 70, 36, NULL, NULL, NULL, 20, 'trucks', NULL, '#CFD3D6'),
    ('PARKING_YARD', 'Parking yard', 'RORO', 'RORO', 'RECT', 'PLACE', 'F', 90, 50, NULL, NULL, NULL, 300, 'veh', '{"bayWidthM":2.6}', '#CFD3D6'),
    ('INSPECTION_BAY', 'Inspection bay', 'RORO', 'RORO', 'RECT', 'PLACE', 'S', 30, 12, 5, NULL, NULL, 4, 'veh', NULL, '#9AA3AD'),
    ('DISPATCH_LANE', 'Dispatch lane', 'RORO', 'RORO', 'LINE', 'DRAW', 'F', NULL, NULL, NULL, NULL, 6, NULL, NULL, NULL, '#8F8F89'),
    ('TERMINAL_BOUNDARY', 'Terminal boundary', 'ZONES', 'ZONE', 'RECT', 'PLACE', 'F', 400, 200, NULL, NULL, NULL, NULL, NULL, NULL, '#E3D9BC'),
    ('SURFACE_AREA', 'Surface area', 'ZONES', 'ZONE', 'RECT', 'PLACE', 'F', 100, 60, NULL, NULL, NULL, NULL, NULL, '{"surface":"CONCRETE"}', '#D9D3C6'),
    ('EXCLUSION_ZONE', 'Exclusion zone', 'ZONES', 'SAFETY', 'POLYGON', 'DRAW', 'FZ', NULL, NULL, NULL, NULL, NULL, NULL, NULL, '{"allowedTypes":[]}', '#C8423B'),
    ('FIRE_ACCESS', 'Fire access', 'ZONES', 'SAFETY', 'LINE', 'DRAW', 'F', NULL, NULL, NULL, NULL, 5, NULL, NULL, NULL, '#C8423B'),
    ('DRAINAGE_ZONE', 'Drainage zone', 'ZONES', 'SAFETY', 'POLYGON', 'DRAW', 'FZ', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, '#6FA8C9'),
    ('FENCE', 'Fence', 'ZONES', 'SAFETY', 'LINE', 'DRAW', '', NULL, NULL, 2.4, NULL, 0.3, NULL, NULL, NULL, '#6B7480')
) AS t(code, label, grp, cat, geom, mode, flags, l, w, h, r, lw, cap, cap_unit, params, colour);

-- Type specific limits and hints
UPDATE layout.asset_type SET min_length_m = 20 WHERE code = 'BERTH';
UPDATE layout.asset_type SET min_width_m = 0.1 WHERE code = 'FENCE';

UPDATE layout.asset_type SET hint = 'Click the corners of the footprint, then double-click or press Enter. An L shape stays an L shape in 3D.'
    WHERE code = 'WAREHOUSE_POLY';
UPDATE layout.asset_type SET hint = 'Click along the quay edge, then double-click or press Enter. Fenders are spaced every 6 m.'
    WHERE code = 'FENDER';
UPDATE layout.asset_type SET hint = 'Place it on a berth: a rail-mounted quay crane must stand on a berth.'
    WHERE code = 'QUAY_CRANE_RAIL';
UPDATE layout.asset_type SET hint = 'Click on the quay edge to place a berth segment, then adjust its length.'
    WHERE code = 'BERTH';
UPDATE layout.asset_type SET hint = 'Click the corners of the zone, then double-click or press Enter. Allowed asset types are set in the inspector.'
    WHERE code = 'EXCLUSION_ZONE';
