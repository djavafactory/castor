package com.djavafactory.castor.core.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import com.djavafactory.castor.core.geometry.domain.QuayFrame;

/**
 * Helpers for tests that need layout rows. Layout tables are emptied without touching reference data (port, lookups),
 * and rows are created with plain SQL so the database guard rules are what is under test.
 */
public final class LayoutTestSupport {

    /** The Westports frame of SETUP-03, the same values as the seeded ref.port row. */
    public static final QuayFrame FRAME = new QuayFrame(101.3066304, 2.9534057, -120.299);

    private static final String[] TABLES = {
        "ops.dead_letter",
        "ops.reconciliation_item",
        "ops.stream_cursor",
        "ops.state_change",
        "ops.processed_event",
        "ops.canonical_event",
        "ops.gate_visit",
        "ops.inventory_adjustment",
        "ops.storage_inventory",
        "ops.cargo_movement",
        "ops.cargo_lot",
        "ops.equipment_status_interval",
        "ops.equipment_state",
        "ops.vessel_call_value",
        "ops.vessel_call",
        "layout.asset_link",
        "layout.equipment_detail",
        "layout.mobile_detail",
        "layout.zone_allowed_type",
        "layout.surface_detail",
        "layout.flow_detail",
        "layout.warehouse_detail",
        "layout.container_yard_detail",
        "layout.storage_pad_detail",
        "layout.storage_allowed_cargo",
        "layout.storage_detail",
        "layout.crane_detail",
        "layout.berth_cargo_family",
        "layout.berth_detail",
        "layout.asset_revision",
        "layout.asset",
        "layout.layout_version",
        "audit.audit_log"
    };

    private LayoutTestSupport() {}

    /** Removes every layout row, the published pointer of the port and the seed import batches. */
    public static void clear(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                // replica role skips the guard and foreign key triggers, so the order of the deletes does not matter
                s.execute("set local session_replication_role = replica");
                s.executeUpdate("update ref.port set current_published_layout_id = null");
                for (String table : TABLES) {
                    s.executeUpdate("delete from " + table);
                }
                s.executeUpdate("delete from ref.import_batch where kind = 'LAYOUT_SEED'");
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not clear the layout tables", e);
        }
    }

    public static long portId(Connection c) throws SQLException {
        return scalar(c, "select id from ref.port where code = 'westports'", Long.class);
    }

    /** A new version of Westports with the given status; published versions get published_by and published_at. */
    public static UUID version(Connection c, int versionNo, String status) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("insert into layout.layout_version (id, port_id, version_no, "
                + "status, created_by, published_by, published_at) values (?, ?, ?, ?, 'test', "
                + "case when ? in ('PUBLISHED','SUPERSEDED') then 'test' end, "
                + "case when ? in ('PUBLISHED','SUPERSEDED') then now() end)")) {
            ps.setObject(1, id);
            ps.setLong(2, portId(c));
            ps.setInt(3, versionNo);
            ps.setString(4, status);
            ps.setString(5, status);
            ps.setString(6, status);
            ps.executeUpdate();
        }
        return id;
    }

    public static UUID asset(Connection c, String code, String type) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement(
                "insert into layout.asset (id, port_id, code, type_code, created_by) values (?, ?, ?, ?, 'test')")) {
            ps.setObject(1, id);
            ps.setLong(2, portId(c));
            ps.setString(3, code);
            ps.setString(4, type);
            ps.executeUpdate();
        }
        return id;
    }

    /** A revision with the given WKT geometry; the anchor is the point on its surface. */
    public static void revision(Connection c, UUID asset, UUID version, String name, String wkt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("insert into layout.asset_revision (asset_id, "
                + "layout_version_id, name, geom, anchor, accuracy_code, source_code, created_by, updated_by) "
                + "select ?, ?, ?, g, ST_PointOnSurface(g), 'APPROXIMATE', 'SYNTHETIC', 'test', 'test' "
                + "from (select ST_GeomFromText(?, 4326) as g) t")) {
            ps.setObject(1, asset);
            ps.setObject(2, version);
            ps.setString(3, name);
            ps.setString(4, wkt);
            ps.executeUpdate();
        }
    }

    /** A rectangle in local quay metres as WKT, x0..x1 along the quay and y0..y1 inland. */
    public static String localRect(double x0, double y0, double x1, double y1) {
        double[][] corners = {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}, {x0, y0}};
        var sb = new StringBuilder("POLYGON((");
        for (int i = 0; i < corners.length; i++) {
            var p = FRAME.toLonLat(corners[i][0], corners[i][1]);
            sb.append(i == 0 ? "" : ", ").append(String.format(java.util.Locale.ROOT, "%.7f %.7f", p.lon(), p.lat()));
        }
        return sb.append("))").toString();
    }

    /** A straight line in local quay metres as WKT. */
    public static String localLine(double x0, double y0, double x1, double y1) {
        var a = FRAME.toLonLat(x0, y0);
        var b = FRAME.toLonLat(x1, y1);
        return String.format(
                java.util.Locale.ROOT, "LINESTRING(%.7f %.7f, %.7f %.7f)", a.lon(), a.lat(), b.lon(), b.lat());
    }

    public static <T> T scalar(Connection c, String sql, Class<T> type) throws SQLException {
        try (var s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getObject(1, type);
        }
    }
}
