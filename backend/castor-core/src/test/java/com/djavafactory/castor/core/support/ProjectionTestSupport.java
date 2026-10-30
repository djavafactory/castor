package com.djavafactory.castor.core.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

/** A published layout with the few assets the projection tests talk about. */
public final class ProjectionTestSupport {

    /** Inside the Westports extent (the origin of the quay frame). */
    public static final double LON = 101.3066304;

    public static final double LAT = 2.9534057;

    public record Layout(long port, UUID crane, UUID berth, UUID pad, List<UUID> cranes) {}

    private ProjectionTestSupport() {}

    /** Empties the layout and operational tables, then publishes a layout with a berth, a pad and {@code cranes} cranes. */
    public static Layout publishedLayout(DataSource dataSource, int cranes) {
        LayoutTestSupport.clear(dataSource);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                // the draft-only guards of the layout are not what these tests are about
                s.execute("set local session_replication_role = replica");
            }
            long port = LayoutTestSupport.portId(c);
            UUID version = LayoutTestSupport.version(c, 1, "PUBLISHED");
            UUID berth = place(c, version, "B1", "BERTH", 0);
            UUID pad = place(c, version, "PAD1", "STORAGE_PAD", 1);
            List<UUID> machines = new ArrayList<>();
            for (int i = 0; i < Math.max(1, cranes); i++) {
                machines.add(place(c, version, "STS" + (i + 1), "STS_CRANE", 2 + i));
            }
            try (var s = c.createStatement()) {
                s.executeUpdate("update ref.port set current_published_layout_id = '" + version + "'");
            }
            c.commit();
            return new Layout(port, machines.get(0), berth, pad, machines);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not publish the test layout", e);
        }
    }

    /** Empties what the projector wrote and keeps the layout. */
    public static void clearState(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                s.execute("set local session_replication_role = replica");
                for (String table : new String[] {
                    "ops.dead_letter",
                    "ops.reconciliation_item",
                    "ops.stream_cursor",
                    "ops.state_change",
                    "ops.processed_event",
                    "ops.canonical_event",
                    "ops.equipment_status_interval",
                    "ops.equipment_state",
                    "ops.vessel_call"
                }) {
                    s.executeUpdate("delete from " + table);
                }
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not clear the operational state", e);
        }
    }

    public static long count(DataSource dataSource, String sql) {
        try (Connection c = dataSource.getConnection();
                var s = c.createStatement();
                var rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    public static List<String> lines(DataSource dataSource, String sql) {
        try (Connection c = dataSource.getConnection();
                var s = c.createStatement();
                var rs = s.executeQuery(sql)) {
            List<String> lines = new ArrayList<>();
            while (rs.next()) {
                lines.add(rs.getString(1));
            }
            return lines;
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    /**
     * Everything the equipment projections hold, as text, one entry per row: two runs that ended in the same state
     * give the same list. Vessel calls are left out, their ids are random.
     */
    public static List<String> equipmentFingerprint(DataSource dataSource) {
        List<String> all = new ArrayList<>();
        all.addAll(lines(
                dataSource,
                "select 'E ' || asset_id || ' ' || coalesce(status, '-') || ' ' || coalesce(status_reason, '-') || ' '"
                        + " || coalesce(ST_AsText(position), '-') || ' ' || coalesce(heading_deg::text, '-') || ' '"
                        + " || coalesce(assignment::text, '-') || ' ' || source || ' ' || quality || ' ' || observed_at"
                        + " || ' ' || last_occurred_at || ' ' || coalesce(last_source_sequence::text, '-') || ' ' || version"
                        + " from ops.equipment_state order by asset_id"));
        all.addAll(lines(
                dataSource,
                "select 'I ' || asset_id || ' ' || status || ' ' || coalesce(reason, '-') || ' ' || started_at || ' '"
                        + " || coalesce(ended_at::text, '-') from ops.equipment_status_interval"
                        + " order by asset_id, started_at, id"));
        all.addAll(lines(
                dataSource,
                "select 'C ' || sequence || ' ' || entity_type || ' ' || entity_id || ' ' || entity_version || ' '"
                        + " || body::text from ops.state_change order by sequence"));
        all.addAll(lines(
                dataSource,
                "select 'R ' || kind || ' ' || entity_id || ' ' || event_id from ops.reconciliation_item"
                        + " order by event_id"));
        all.add("processed " + count(dataSource, "select count(*) from ops.processed_event"));
        all.add("canonical " + count(dataSource, "select count(*) from ops.canonical_event"));
        all.add("dead " + count(dataSource, "select count(*) from ops.dead_letter"));
        return all;
    }

    private static UUID place(Connection c, UUID version, String code, String type, int slot) throws SQLException {
        UUID asset = LayoutTestSupport.asset(c, code, type);
        LayoutTestSupport.revision(
                c, asset, version, code, LayoutTestSupport.localRect(slot * 30.0, 0, slot * 30.0 + 20, 20));
        return asset;
    }
}
