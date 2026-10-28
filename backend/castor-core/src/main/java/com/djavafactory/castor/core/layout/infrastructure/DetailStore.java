package com.djavafactory.castor.core.layout.infrastructure;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.djavafactory.castor.core.layout.domain.AssetDetails;
import com.djavafactory.castor.core.layout.domain.AssetDetails.Berth;
import com.djavafactory.castor.core.layout.domain.AssetDetails.ContainerYard;
import com.djavafactory.castor.core.layout.domain.AssetDetails.Crane;
import com.djavafactory.castor.core.layout.domain.AssetDetails.Equipment;
import com.djavafactory.castor.core.layout.domain.AssetDetails.Flow;
import com.djavafactory.castor.core.layout.domain.AssetDetails.Mobile;
import com.djavafactory.castor.core.layout.domain.AssetDetails.Storage;
import com.djavafactory.castor.core.layout.domain.AssetDetails.StoragePad;
import com.djavafactory.castor.core.layout.domain.AssetDetails.Surface;
import com.djavafactory.castor.core.layout.domain.AssetDetails.Warehouse;
import com.djavafactory.castor.core.layout.domain.AssetDetails.Zone;

/**
 * Reads and writes the typed detail tables (and links) of CORE-04 part 2 with plain SQL on the caller's connection, so
 * a write joins the transaction of the entity writes. One query per table for a whole version keeps a layout read at
 * about twenty statements regardless of the number of assets.
 */
final class DetailStore {

    private DetailStore() {}

    /** Mutable collector, turned into an {@link AssetDetails} once every table has been read. */
    private static final class Draft {
        Berth berth;
        final List<String> families = new ArrayList<>();
        Crane crane;
        Storage storage;
        final List<String> allowedCargo = new ArrayList<>();
        StoragePad pad;
        ContainerYard yard;
        Warehouse warehouse;
        Flow flow;
        Surface surface;
        final List<String> allowedTypes = new ArrayList<>();
        boolean zone;
        Mobile mobile;
        Equipment equipment;

        AssetDetails build() {
            Berth b = berth == null ? null : new Berth(berth.depthM(), berth.maxLoaM(), List.copyOf(families));
            Storage s = storage == null
                    ? null
                    : new Storage(
                            storage.capacityValue(),
                            storage.capacityUnit(),
                            storage.usableRatio(),
                            storage.designFill(),
                            storage.cargoTypeCode(),
                            List.copyOf(allowedCargo),
                            storage.maxFloorLoadTM2(),
                            storage.maxStackM(),
                            storage.bearingCapacityKpa(),
                            storage.weatherExposed());
            Zone z = zone ? new Zone(List.copyOf(allowedTypes)) : null;
            return new AssetDetails(b, crane, s, pad, yard, warehouse, flow, surface, z, mobile, equipment);
        }
    }

    /** Details of every asset of the version, by asset id; assets without any detail are absent from the map. */
    static Map<UUID, AssetDetails> read(Connection c, UUID version) throws SQLException {
        return read(c, version, null);
    }

    /** Details of one asset of the version, or of every asset when {@code only} is null. */
    static Map<UUID, AssetDetails> read(Connection c, UUID version, UUID only) throws SQLException {
        Map<UUID, Draft> drafts = new HashMap<>();
        Function<UUID, Draft> of = id -> drafts.computeIfAbsent(id, k -> new Draft());

        query(
                c,
                "select asset_id, depth_m, max_loa_m from layout.berth_detail where layout_version_id = ?",
                version,
                only,
                rs -> of.apply(id(rs)).berth = new Berth(rs.getBigDecimal(2), rs.getBigDecimal(3), List.of()));
        query(
                c,
                "select asset_id, family_code from layout.berth_cargo_family where layout_version_id = ? order by 2",
                version,
                only,
                rs -> of.apply(id(rs)).families.add(rs.getString(2)));
        query(
                c,
                "select asset_id, gauge_m, outreach_m, backreach_m, reach_m, safe_working_load_t "
                        + "from layout.crane_detail where layout_version_id = ?",
                version,
                only,
                rs -> of.apply(id(rs)).crane = new Crane(
                        rs.getBigDecimal(2),
                        rs.getBigDecimal(3),
                        rs.getBigDecimal(4),
                        rs.getBigDecimal(5),
                        rs.getBigDecimal(6)));
        query(
                c,
                "select asset_id, capacity_value, capacity_unit, usable_ratio, design_fill, cargo_type_code, "
                        + "max_floor_load_t_m2, max_stack_m, bearing_capacity_kpa, weather_exposed "
                        + "from layout.storage_detail where layout_version_id = ?",
                version,
                only,
                rs -> of.apply(id(rs)).storage = new Storage(
                        rs.getBigDecimal(2),
                        rs.getString(3),
                        rs.getBigDecimal(4),
                        rs.getBigDecimal(5),
                        rs.getString(6),
                        List.of(),
                        rs.getBigDecimal(7),
                        rs.getBigDecimal(8),
                        rs.getBigDecimal(9),
                        rs.getBoolean(10)));
        query(
                c,
                "select asset_id, cargo_type_code from layout.storage_allowed_cargo where layout_version_id = ? "
                        + "order by 2",
                version,
                only,
                rs -> of.apply(id(rs)).allowedCargo.add(rs.getString(2)));
        query(
                c,
                "select asset_id, bays, rows, bay_pitch_m, row_pitch_m, max_height_m from layout.storage_pad_detail "
                        + "where layout_version_id = ?",
                version,
                only,
                rs -> of.apply(id(rs)).pad = new StoragePad(
                        rs.getInt(2), rs.getInt(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6)));
        query(
                c,
                "select asset_id, max_tiers, design_fill, capacity_teu from layout.container_yard_detail "
                        + "where layout_version_id = ?",
                version,
                only,
                rs -> of.apply(id(rs)).yard = new ContainerYard(rs.getInt(2), rs.getBigDecimal(3), rs.getInt(4)));
        query(
                c,
                "select asset_id, roof_rise_m, dock_doors, door_side from layout.warehouse_detail where layout_version_id = ?",
                version,
                only,
                rs -> of.apply(id(rs)).warehouse = new Warehouse(rs.getBigDecimal(2), rs.getInt(3), rs.getString(4)));
        query(
                c,
                "select asset_id, rate_value, rate_unit from layout.flow_detail where layout_version_id = ?",
                version,
                only,
                rs -> of.apply(id(rs)).flow = new Flow(rs.getBigDecimal(2), rs.getString(3)));
        query(
                c,
                "select asset_id, surface_code from layout.surface_detail where layout_version_id = ?",
                version,
                only,
                rs -> of.apply(id(rs)).surface = new Surface(rs.getString(2)));
        query(
                c,
                "select asset_id, allowed_type_code from layout.zone_allowed_type where layout_version_id = ? "
                        + "order by 2",
                version,
                only,
                rs -> {
                    Draft d = of.apply(id(rs));
                    d.zone = true;
                    d.allowedTypes.add(rs.getString(2));
                });
        query(
                c,
                "select asset_id, position_mode, uncertainty_m from layout.mobile_detail where layout_version_id = ?",
                version,
                only,
                rs -> of.apply(id(rs)).mobile = new Mobile(rs.getString(2), rs.getBigDecimal(3)));
        query(
                c,
                "select asset_id, model, operator, rated_throughput, throughput_unit, design_status "
                        + "from layout.equipment_detail where layout_version_id = ?",
                version,
                only,
                rs -> of.apply(id(rs)).equipment = new Equipment(
                        rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getString(5), rs.getString(6)));

        Map<UUID, AssetDetails> out = new HashMap<>();
        drafts.forEach((asset, d) -> out.put(asset, d.build()));
        return out;
    }

    /** Links of the version grouped by source asset: {kind, toAssetId} in a stable order. */
    static Map<UUID, List<Map.Entry<String, UUID>>> readLinks(Connection c, UUID version) throws SQLException {
        return readLinks(c, version, null);
    }

    /** Links that start at one asset of the version, or at every asset when {@code only} is null. */
    static Map<UUID, List<Map.Entry<String, UUID>>> readLinks(Connection c, UUID version, UUID only)
            throws SQLException {
        Map<UUID, List<Map.Entry<String, UUID>>> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("select from_asset_id, kind, to_asset_id from layout.asset_link "
                + "where layout_version_id = ?" + (only == null ? "" : " and from_asset_id = ?")
                + " order by from_asset_id, kind, to_asset_id")) {
            ps.setObject(1, version);
            if (only != null) {
                ps.setObject(2, only);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                            .add(Map.entry(rs.getString(2), rs.getObject(3, UUID.class)));
                }
            }
        }
        return out;
    }

    /** Writes every detail group the asset has. The revision row must already be flushed. */
    static void write(Connection c, UUID version, UUID asset, AssetDetails d) throws SQLException {
        if (d.berth() != null) {
            exec(
                    c,
                    "insert into layout.berth_detail (asset_id, layout_version_id, depth_m, max_loa_m) values (?,?,?,?)",
                    asset,
                    version,
                    d.berth().depthM(),
                    d.berth().maxLoaM());
            for (String family : d.berth().cargoFamilies()) {
                exec(c, "insert into layout.berth_cargo_family values (?,?,?)", asset, version, family);
            }
        }
        if (d.crane() != null) {
            exec(
                    c,
                    "insert into layout.crane_detail values (?,?,?,?,?,?,?)",
                    asset,
                    version,
                    d.crane().gaugeM(),
                    d.crane().outreachM(),
                    d.crane().backreachM(),
                    d.crane().reachM(),
                    d.crane().safeWorkingLoadT());
        }
        if (d.storage() != null) {
            Storage s = d.storage();
            exec(
                    c,
                    "insert into layout.storage_detail (asset_id, layout_version_id, capacity_value, capacity_unit, "
                            + "usable_ratio, design_fill, cargo_type_code, max_floor_load_t_m2, max_stack_m, "
                            + "bearing_capacity_kpa, weather_exposed) values (?,?,?,?,?,?,?,?,?,?,?)",
                    asset,
                    version,
                    s.capacityValue(),
                    s.capacityUnit(),
                    s.usableRatio(),
                    s.designFill(),
                    s.cargoTypeCode(),
                    s.maxFloorLoadTM2(),
                    s.maxStackM(),
                    s.bearingCapacityKpa(),
                    s.weatherExposed());
            for (String cargo : s.allowedCargoTypes()) {
                exec(c, "insert into layout.storage_allowed_cargo values (?,?,?)", asset, version, cargo);
            }
        }
        if (d.storagePad() != null) {
            StoragePad p = d.storagePad();
            exec(
                    c,
                    "insert into layout.storage_pad_detail values (?,?,?,?,?,?,?)",
                    asset,
                    version,
                    (short) p.bays(),
                    (short) p.rows(),
                    p.bayPitchM(),
                    p.rowPitchM(),
                    p.maxHeightM());
        }
        if (d.containerYard() != null) {
            ContainerYard y = d.containerYard();
            exec(
                    c,
                    "insert into layout.container_yard_detail values (?,?,?,?,?)",
                    asset,
                    version,
                    (short) y.maxTiers(),
                    y.designFill(),
                    y.capacityTeu());
        }
        if (d.warehouse() != null) {
            exec(
                    c,
                    "insert into layout.warehouse_detail (asset_id, layout_version_id, roof_rise_m, dock_doors, "
                            + "door_side) values (?,?,?,?,?)",
                    asset,
                    version,
                    d.warehouse().roofRiseM(),
                    (short) d.warehouse().dockDoors(),
                    d.warehouse().doorSide());
        }
        if (d.flow() != null) {
            exec(
                    c,
                    "insert into layout.flow_detail values (?,?,?,?)",
                    asset,
                    version,
                    d.flow().rateValue(),
                    d.flow().rateUnit());
        }
        if (d.surface() != null) {
            exec(
                    c,
                    "insert into layout.surface_detail values (?,?,?)",
                    asset,
                    version,
                    d.surface().surfaceCode());
        }
        if (d.zone() != null) {
            for (String type : d.zone().allowedTypes()) {
                exec(c, "insert into layout.zone_allowed_type values (?,?,?)", asset, version, type);
            }
        }
        if (d.mobile() != null) {
            exec(
                    c,
                    "insert into layout.mobile_detail values (?,?,?,?)",
                    asset,
                    version,
                    d.mobile().positionMode(),
                    d.mobile().uncertaintyM());
        }
        if (d.equipment() != null) {
            Equipment e = d.equipment();
            exec(
                    c,
                    "insert into layout.equipment_detail values (?,?,?,?,?,?,?)",
                    asset,
                    version,
                    e.model(),
                    e.operator(),
                    e.ratedThroughput(),
                    e.throughputUnit(),
                    e.designStatus());
        }
    }

    private static final List<String> DETAIL_TABLES = List.of(
            "berth_detail",
            "berth_cargo_family",
            "crane_detail",
            "storage_detail",
            "storage_allowed_cargo",
            "storage_pad_detail",
            "container_yard_detail",
            "warehouse_detail",
            "flow_detail",
            "surface_detail",
            "zone_allowed_type",
            "mobile_detail",
            "equipment_detail");

    /** Removes every detail row and the outgoing links of an asset revision, before they are written again. */
    static void clear(Connection c, UUID version, UUID asset) throws SQLException {
        for (String table : DETAIL_TABLES) {
            exec(c, "delete from layout." + table + " where asset_id = ? and layout_version_id = ?", asset, version);
        }
        exec(c, "delete from layout.asset_link where from_asset_id = ? and layout_version_id = ?", asset, version);
    }

    static void writeLink(Connection c, UUID version, UUID from, UUID to, String kind) throws SQLException {
        exec(c, "insert into layout.asset_link values (?,?,?,?)", version, from, to, kind);
    }

    private interface RowHandler {
        void accept(ResultSet rs) throws SQLException;
    }

    private static UUID id(ResultSet rs) throws SQLException {
        return rs.getObject(1, UUID.class);
    }

    private static void query(Connection c, String sql, UUID version, UUID only, RowHandler handler)
            throws SQLException {
        // every detail query filters by "layout_version_id = ?": a single asset adds its own id
        String text =
                only == null ? sql : sql.replace("layout_version_id = ?", "layout_version_id = ? and asset_id = ?");
        try (PreparedStatement ps = c.prepareStatement(text)) {
            ps.setObject(1, version);
            if (only != null) {
                ps.setObject(2, only);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    handler.accept(rs);
                }
            }
        }
    }

    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                Object a = args[i];
                if (a == null) {
                    ps.setNull(i + 1, Types.NULL);
                } else if (a instanceof BigDecimal b) {
                    ps.setBigDecimal(i + 1, b);
                } else {
                    ps.setObject(i + 1, a);
                }
            }
            ps.executeUpdate();
        }
    }
}
