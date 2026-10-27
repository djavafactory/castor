package it.replica8.castor.core.port.infrastructure;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import it.replica8.castor.core.port.domain.AccuracyClass;
import it.replica8.castor.core.port.domain.AssetCategory;
import it.replica8.castor.core.port.domain.CargoFamily;
import it.replica8.castor.core.port.domain.DataSourceCode;
import it.replica8.castor.core.port.domain.PaletteGroup;
import it.replica8.castor.core.port.domain.ReferenceKind;
import it.replica8.castor.core.port.domain.Surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The reference tables of migration V3 against a real PostGIS. */
@QuarkusTest
class ReferenceDataIT {

    private static final String PORT_INSERT = "insert into ref.port (code, name, city, time_zone, origin, "
            + "measure_srid, frame_rot_deg, extent) values (?, 'X', 'Y', ?, ST_SetSRID(ST_MakePoint(101.3, 2.95), 4326), "
            + "?, ?, ST_GeomFromText(?, 4326))";

    private static final String SQUARE = "POLYGON((101.26 2.90, 101.36 2.90, 101.36 3.02, 101.26 3.02, 101.26 2.90))";
    private static final String BOWTIE = "POLYGON((101.26 2.90, 101.36 3.02, 101.36 2.90, 101.26 3.02, 101.26 2.90))";

    @Inject
    DataSource dataSource;

    private Set<String> column(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
                ResultSet rs = c.createStatement().executeQuery(sql)) {
            Set<String> values = new LinkedHashSet<>();
            while (rs.next()) {
                values.add(rs.getString(1));
            }
            return values;
        }
    }

    private static Set<String> names(Enum<?>[] values) {
        Set<String> names = new LinkedHashSet<>();
        Arrays.stream(values).forEach(v -> names.add(v.name()));
        return names;
    }

    private int count(String table) throws SQLException {
        try (Connection c = dataSource.getConnection();
                ResultSet rs = c.createStatement().executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void javaEnumsMatchTheSeededRows() throws SQLException {
        assertEquals(names(DataSourceCode.values()), column("select code from ref.data_source"));
        assertEquals(names(AccuracyClass.values()), column("select code from ref.accuracy_class"));
        assertEquals(names(AssetCategory.values()), column("select code from ref.asset_category"));
        assertEquals(names(PaletteGroup.values()), column("select code from ref.palette_group"));
        assertEquals(names(CargoFamily.values()), column("select code from ref.cargo_family"));
        assertEquals(names(Surface.values()), column("select code from ref.surface"));
        String definition = column("select pg_get_constraintdef(oid) from pg_constraint "
                        + "where conname = 'reference_feature_kind_check'")
                .iterator()
                .next();
        for (String kind : names(ReferenceKind.values())) {
            assertTrue(definition.contains("'" + kind + "'"), kind + " is allowed by the kind check");
        }
        assertEquals(ReferenceKind.values().length, definition.split("'", -1).length / 2, "no other kind is allowed");
    }

    @Test
    void lookupTablesAreSeeded() throws SQLException {
        assertEquals(8, count("ref.data_source"));
        assertEquals(4, count("ref.accuracy_class"));
        assertEquals(11, count("ref.asset_category"));
        assertEquals(8, count("ref.palette_group"));
        assertEquals(4, count("ref.surface"));
        assertEquals(5, count("ref.cargo_family"));
        assertEquals(13, count("ref.cargo_type"));
        assertEquals(10, count("ref.ship_class"));
        assertEquals(
                Set.of("OSM", "SYNTHETIC", "OPERATOR", "SURVEY", "WESTPORTS_GIS"),
                column("select code from ref.data_source where may_persist"));
    }

    @Test
    void westportsIsSeededWithItsFrame() throws SQLException {
        try (Connection c = dataSource.getConnection();
                ResultSet rs = c.createStatement()
                        .executeQuery(
                                "select city, time_zone, measure_srid, frame_rot_deg, ST_X(origin), ST_Y(origin), "
                                        + "synthetic, ST_IsValid(extent), ST_IsValid(land_extent), ST_IsValid(quay_line), "
                                        + "ST_Contains(extent, land_extent) from ref.port where code = 'westports'")) {
            assertTrue(rs.next());
            assertEquals("Port Klang", rs.getString(1));
            assertEquals("Asia/Kuala_Lumpur", rs.getString(2));
            assertEquals(3380, rs.getInt(3));
            assertEquals(-120.299, rs.getDouble(4), 1e-9);
            assertEquals(101.3066304, rs.getDouble(5), 1e-9);
            assertEquals(2.9534057, rs.getDouble(6), 1e-9);
            assertTrue(rs.getBoolean(7));
            assertTrue(rs.getBoolean(8) && rs.getBoolean(9) && rs.getBoolean(10));
            assertTrue(rs.getBoolean(11), "the land area lies inside the port extent");
        }
        assertEquals(4, count("ref.control_point"));
    }

    private void insertPort(String code, String zone, int srid, double rot, String extent) throws SQLException {
        try (Connection c = dataSource.getConnection();
                var ps = c.prepareStatement(PORT_INSERT)) {
            ps.setString(1, code);
            ps.setString(2, zone);
            ps.setInt(3, srid);
            ps.setDouble(4, rot);
            ps.setString(5, extent);
            ps.executeUpdate();
        }
    }

    private void assertRejected(String code, String zone, int srid, double rot, String extent) {
        var e = assertThrows(SQLException.class, () -> insertPort(code, zone, srid, rot, extent));
        assertEquals("23514", e.getSQLState(), e.getMessage());
    }

    @Test
    void portWithAnInvalidExtentCannotBeInserted() {
        assertRejected("bowtie", "Asia/Kuala_Lumpur", 3380, -120.299, BOWTIE);
    }

    @Test
    void portRulesAreEnforcedByTheDatabase() {
        assertRejected("Bad Code", "Asia/Kuala_Lumpur", 3380, 0, SQUARE);
        assertRejected("bad-rot", "Asia/Kuala_Lumpur", 3380, 360, SQUARE);
        assertRejected("bad-zone", "Mars/Olympus_Mons", 3380, 0, SQUARE);
        assertRejected("bad-srid", "Asia/Kuala_Lumpur", 999999, 0, SQUARE);
    }

    @Test
    void validPortCanBeInsertedAndHasNoStoredUtcOffset() throws SQLException {
        insertPort("rome-test", "Europe/Rome", 3380, 12.5, SQUARE);
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().executeUpdate("delete from ref.port where code = 'rome-test'");
        }
        assertEquals(
                0,
                column("select column_name from information_schema.columns where table_schema = 'ref' "
                                + "and table_name = 'port' and column_name ilike '%offset%'")
                        .size());
    }
}
