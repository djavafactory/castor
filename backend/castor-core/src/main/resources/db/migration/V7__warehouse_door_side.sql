-- Warehouse door side (CASTOR-30): the compass side the dock doors face, unrotated. Existing warehouses get NORTH.
-- The detail_guard trigger of V5 stays on warehouse_detail; this migration only adds the column.

ALTER TABLE layout.warehouse_detail
    ADD COLUMN door_side varchar(5) NOT NULL DEFAULT 'NORTH'
        CONSTRAINT warehouse_door_side_check CHECK (door_side IN ('NORTH', 'EAST', 'SOUTH', 'WEST'));

-- Copy used by draft creation and restore: same as V5, with the door side of every warehouse copied too.
CREATE OR REPLACE FUNCTION layout.copy_revisions(p_from uuid, p_to uuid) RETURNS integer
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
    INSERT INTO layout.warehouse_detail SELECT asset_id, p_to, roof_rise_m, dock_doors, door_side
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
