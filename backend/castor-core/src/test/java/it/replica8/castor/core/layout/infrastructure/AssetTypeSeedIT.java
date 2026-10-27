package it.replica8.castor.core.layout.infrastructure;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The asset type registry of migration V4 against a real PostGIS. */
@QuarkusTest
class AssetTypeSeedIT {

    @Inject
    DataSource dataSource;

    private void update(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().executeUpdate(sql);
        }
    }

    private Map<String, String> row(String code, String... columns) throws SQLException {
        try (Connection c = dataSource.getConnection();
                ResultSet rs = c.createStatement()
                        .executeQuery("select " + String.join(", ", columns) + " from layout.asset_type where code = '"
                                + code + "'")) {
            assertTrue(rs.next(), code + " exists");
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < columns.length; i++) {
                values.put(columns[i], rs.getString(i + 1));
            }
            return values;
        }
    }

    private Map<String, Integer> countsBy(String column) throws SQLException {
        try (Connection c = dataSource.getConnection();
                ResultSet rs = c.createStatement()
                        .executeQuery("select " + column + ", count(*) from layout.asset_type group by 1 order by 1")) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            while (rs.next()) {
                counts.put(rs.getString(1), rs.getInt(2));
            }
            return counts;
        }
    }

    @Test
    void everyListedTypeIsSeededWithItsModelKeyAndHint() throws SQLException {
        // The ticket text says 56 in total but lists 56 types plus the 2 new ones: 58 rows are seeded.
        assertEquals(58, countsBy("'all'").get("all"));
        assertEquals(
                0,
                countsBy("case when model_key = lower(code) and length(hint) > 0 then 'ok' else 'bad' end")
                        .getOrDefault("bad", 0));
        assertEquals(58, countsBy("model_rev").get("1"));
    }

    @Test
    void paletteGroupsHaveTheExpectedTypes() throws SQLException {
        var counts = countsBy("palette_group_code");
        assertEquals(8, counts.get("CRANES"));
        assertEquals(6, counts.get("HANDLING"));
        assertEquals(16, counts.get("STORAGE"));
        assertEquals(5, counts.get("TRANSPORT"));
        assertEquals(8, counts.get("MARINE"));
        assertEquals(6, counts.get("ROADS"));
        assertEquals(3, counts.get("RORO"));
        assertEquals(6, counts.get("ZONES"));
    }

    @Test
    void theTwoNewMaterialHandlingTypesAreSeeded() throws SQLException {
        var excavator = row(
                "EXCAVATOR",
                "palette_group_code",
                "category_code",
                "geometry_kind",
                "placement_mode",
                "is_mobile",
                "default_length_m",
                "default_width_m",
                "default_height_m",
                "colour_hex");
        assertEquals("HANDLING", excavator.get("palette_group_code"));
        assertEquals("MOBILE", excavator.get("category_code"));
        assertEquals("POINT", excavator.get("geometry_kind"));
        assertEquals("t", excavator.get("is_mobile"));
        assertEquals("10.00", excavator.get("default_length_m"));
        assertEquals("3.20", excavator.get("default_width_m"));
        assertEquals("3.40", excavator.get("default_height_m"));
        assertEquals("#E8A317", excavator.get("colour_hex"));

        var loader = row(
                "MOBILE_CONVEYOR_LOADER",
                "palette_group_code",
                "category_code",
                "default_length_m",
                "params::text",
                "colour_hex");
        assertEquals("HANDLING", loader.get("palette_group_code"));
        assertEquals("DRY_BULK", loader.get("category_code"));
        assertEquals("30.00", loader.get("default_length_m"));
        assertTrue(loader.get("params::text").contains("800"));
        assertEquals("#4F6D8F", loader.get("colour_hex"));
    }

    @Test
    void samplesMatchTheTicketTable() throws SQLException {
        var berth = row(
                "BERTH",
                "geometry_kind",
                "placement_mode",
                "default_length_m",
                "min_length_m",
                "may_extend_over_water",
                "colour_hex");
        assertEquals("QUAY_SEGMENT", berth.get("geometry_kind"));
        assertEquals("200.00", berth.get("default_length_m"));
        assertEquals("20.00", berth.get("min_length_m"));
        assertEquals("t", berth.get("may_extend_over_water"));

        var rail = row("QUAY_CRANE_RAIL", "must_be_on_berth", "hint");
        assertEquals("t", rail.get("must_be_on_berth"));
        assertEquals("Place it on a berth: a rail-mounted quay crane must stand on a berth.", rail.get("hint"));

        var poly = row("WAREHOUSE_POLY", "placement_mode", "is_solid", "hint");
        assertEquals("DRAW", poly.get("placement_mode"));
        assertEquals(
                "Click the corners of the footprint, then double-click or press Enter. An L shape stays an L shape in 3D.",
                poly.get("hint"));

        var zone = row("EXCLUSION_ZONE", "is_zone", "is_flat");
        assertEquals("t", zone.get("is_zone"));
        assertEquals("t", zone.get("is_flat"));

        var fence = row("FENDER", "default_line_width_m", "params::text");
        assertEquals("1.10", fence.get("default_line_width_m"));
        assertTrue(fence.get("params::text").contains("6"));
    }

    @Test
    void flagsFollowTheTable() throws SQLException {
        assertEquals("f", row("RTG", "is_solid").get("is_solid"));
        assertEquals("t", row("HARBOUR_GANTRY_CRANE", "is_solid").get("is_solid"));
        assertEquals("t", row("MOBILE_HARBOUR_CRANE", "is_mobile").get("is_mobile"));
        assertEquals("t", row("RORO_RAMP", "may_extend_over_water").get("may_extend_over_water"));
        assertEquals("t", row("INTERNAL_ROAD", "is_flat").get("is_flat"));
    }

    @Test
    void theDatabaseRefusesInconsistentTypes() {
        String base = "insert into layout.asset_type (code, label, palette_group_code, category_code, geometry_kind, "
                + "placement_mode, model_key, colour_hex, hint, sort_order%s) values ('TEST_%s', 'T', 'CRANES', "
                + "'LIFTING', '%s', '%s', 'test', '#112233', 'h', 1%s)";
        assertRejects(String.format(base, "", "A", "POINT", "DRAW", "")); // only lines and polygons are drawn
        assertRejects(String.format(base, "", "B", "BLOB", "PLACE", "")); // unknown geometry kind
        assertRejects(
                String.format(base, ", is_flat, is_solid", "C", "RECT", "PLACE", ", true, true")); // flat and solid
        assertRejects(String.format(base, ", default_length_m", "D", "RECT", "PLACE", ", 0.1")); // below the minimum
        assertRejects(
                String.format(base, ", default_capacity_value", "E", "RECT", "PLACE", ", 5")); // value without unit
    }

    private void assertRejects(String sql) {
        var e = assertThrows(SQLException.class, () -> update(sql), sql);
        assertEquals("23514", e.getSQLState(), e.getMessage());
    }
}
