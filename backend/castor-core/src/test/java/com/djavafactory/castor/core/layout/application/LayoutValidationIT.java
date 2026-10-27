package com.djavafactory.castor.core.layout.application;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.layout.domain.LayoutAsset;
import com.djavafactory.castor.core.layout.domain.ValidationIssue;
import com.djavafactory.castor.core.layout.domain.ValidationReport;
import com.djavafactory.castor.core.shared.domain.ConflictException;
import com.djavafactory.castor.core.shared.domain.RuleViolationException;
import com.djavafactory.castor.core.support.LayoutTestSupport;
import com.djavafactory.castor.core.support.ShapeFixtures.Asset;

import static com.djavafactory.castor.core.support.ShapeFixtures.line;
import static com.djavafactory.castor.core.support.ShapeFixtures.path;
import static com.djavafactory.castor.core.support.ShapeFixtures.rect;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that look at shapes that meet, answered by PostGIS (DESIGNER-03 part 1): overlap, exclusion zones, roads,
 * and the single-asset gate. The draft holds a few stored shapes; each test checks a candidate against them.
 */
@QuarkusTest
class LayoutValidationIT {

    @Inject
    DataSource dataSource;

    @Inject
    LayoutValidation validation;

    private UUID draft;

    @BeforeEach
    void draft() throws SQLException {
        LayoutTestSupport.clear(dataSource);
        try (Connection c = dataSource.getConnection()) {
            draft = LayoutTestSupport.version(c, 1, "DRAFT");
        }
    }

    @AfterEach
    void clear() {
        LayoutTestSupport.clear(dataSource);
    }

    private UUID stored(String code, String type, String name, String wkt) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            UUID id = LayoutTestSupport.asset(c, code, type);
            LayoutTestSupport.revision(c, id, draft, name, wkt);
            return id;
        }
    }

    private void zoneAllows(UUID zone, String... types) throws SQLException {
        try (Connection c = dataSource.getConnection();
                var ps = c.prepareStatement("insert into layout.zone_allowed_type (asset_id, layout_version_id, "
                        + "allowed_type_code) values (?, ?, ?)")) {
            for (String type : types) {
                ps.setObject(1, zone);
                ps.setObject(2, draft);
                ps.setString(3, type);
                ps.executeUpdate();
            }
        }
    }

    private ValidationReport check(LayoutAsset candidate) {
        return validation.asset(draft, candidate);
    }

    private static List<String> codes(ValidationReport report) {
        return report.issues().stream().map(ValidationIssue::code).toList();
    }

    private static LayoutAsset warehouse(String name, double x0, double y0, double x1, double y1) {
        return Asset.of(null, "WAREHOUSE", name, rect(x0, y0, x1, y1))
                .size(x1 - x0, y1 - y0)
                .build();
    }

    // ---- OVERLAP -----------------------------------------------------------------------------------------------

    @Test
    void solidShapesThatShareAreaOverlap() throws SQLException {
        UUID other = stored("WH-B", "BUILDING", "Warehouse B", LayoutTestSupport.localRect(1000, 100, 1100, 140));

        var report = check(warehouse("Warehouse A", 1090, 100, 1190, 140));

        assertEquals(List.of("OVERLAP"), codes(report));
        assertEquals("Overlaps Warehouse B", report.errors().get(0).message());
        assertEquals(List.of(other.toString()), report.errors().get(0).assetIds());
    }

    @Test
    void solidShapesThatOnlyTouchDoNotOverlap() throws SQLException {
        stored("WH-B", "BUILDING", "Warehouse B", LayoutTestSupport.localRect(1000, 100, 1100, 140));

        assertTrue(
                check(warehouse("Warehouse A", 1100, 100, 1200, 140)).issues().isEmpty());
    }

    @Test
    void aFlatShapeOverASolidOneIsNotAnOverlap() throws SQLException {
        stored("WH-B", "BUILDING", "Warehouse B", LayoutTestSupport.localRect(1000, 100, 1100, 140));
        var parking = Asset.of(null, "PARKING_YARD", "Parking", rect(1050, 100, 1150, 140))
                .size(100.0, 40.0)
                .build();

        assertTrue(check(parking).issues().isEmpty());
    }

    @Test
    void aShapeIsNeverComparedWithItsOwnStoredRevision() throws SQLException {
        UUID own = stored("WH-B", "BUILDING", "Warehouse B", LayoutTestSupport.localRect(1000, 100, 1100, 140));
        var moved = Asset.of(own, "BUILDING", "Warehouse B", rect(1020, 100, 1120, 140))
                .size(100.0, 40.0)
                .build();

        assertTrue(check(moved).issues().isEmpty());
    }

    // ---- IN_EXCLUSION_ZONE -------------------------------------------------------------------------------------

    @Test
    void polygonInsideAZoneThatDoesNotAllowItsTypeIsRefused() throws SQLException {
        UUID zone = stored("EXZ-1", "EXCLUSION_ZONE", "Zone 1", LayoutTestSupport.localRect(3000, 500, 3100, 600));
        zoneAllows(zone, "LIQUID_TANK");

        var report = check(warehouse("Warehouse A", 3050, 520, 3090, 560));

        assertEquals(List.of("IN_EXCLUSION_ZONE"), codes(report));
        assertEquals(
                "Inside safety exclusion zone Zone 1. Move it, or record a documented override in the inspector",
                report.errors().get(0).message());
        assertEquals(List.of(zone.toString()), report.errors().get(0).assetIds());
    }

    @Test
    void polygonOfAnAllowedTypeInsideAZoneIsFine() throws SQLException {
        UUID zone = stored("EXZ-1", "EXCLUSION_ZONE", "Zone 1", LayoutTestSupport.localRect(3000, 500, 3100, 600));
        zoneAllows(zone, "LIQUID_TANK");
        var tank = Asset.of(null, "LIQUID_TANK", "Tank 1", rect(3040, 520, 3066, 546))
                .radius(13)
                .height(15.0)
                .build();

        assertTrue(check(tank).issues().isEmpty());
    }

    @Test
    void aRecordedOverrideLiftsTheZoneRule() throws SQLException {
        UUID zone = stored("EXZ-1", "EXCLUSION_ZONE", "Zone 1", LayoutTestSupport.localRect(3000, 500, 3100, 600));
        zoneAllows(zone, "LIQUID_TANK");
        var hall = Asset.of(null, "WAREHOUSE", "Warehouse A", rect(3050, 520, 3090, 560))
                .size(40.0, 40.0)
                .override("Approved by the safety officer, ref HSE-114")
                .build();

        assertTrue(check(hall).issues().isEmpty());
    }

    @Test
    void polygonOutsideTheZoneIsFine() throws SQLException {
        UUID zone = stored("EXZ-1", "EXCLUSION_ZONE", "Zone 1", LayoutTestSupport.localRect(3000, 500, 3100, 600));
        zoneAllows(zone, "LIQUID_TANK");

        assertTrue(
                check(warehouse("Warehouse A", 3200, 520, 3240, 560)).issues().isEmpty());
    }

    @Test
    void lineCrossingAZoneIsRefusedByAnyVertexOrSegment() throws SQLException {
        UUID zone = stored("EXZ-1", "EXCLUSION_ZONE", "Zone 1", LayoutTestSupport.localRect(3000, 500, 3100, 600));
        zoneAllows(zone, "PIPELINE");
        // both ends are outside the zone, only the segment between them crosses it
        var through = Asset.of(null, "CONVEYOR", "Belt", path(2950, 550, 3150, 550))
                .lineWidth(2.4)
                .build();
        var endsInside = Asset.of(null, "CONVEYOR", "Belt", path(2950, 550, 3050, 550))
                .lineWidth(2.4)
                .build();

        assertEquals(List.of("IN_EXCLUSION_ZONE"), codes(check(through)));
        assertEquals(List.of("IN_EXCLUSION_ZONE"), codes(check(endsInside)));
    }

    @Test
    void lineBesideAZoneIsFine() throws SQLException {
        UUID zone = stored("EXZ-1", "EXCLUSION_ZONE", "Zone 1", LayoutTestSupport.localRect(3000, 500, 3100, 600));
        zoneAllows(zone, "PIPELINE");
        var beside = Asset.of(null, "CONVEYOR", "Belt", line(2950, 450, 3150, 450))
                .lineWidth(2.4)
                .build();

        assertTrue(check(beside).issues().isEmpty());
    }

    // ---- ON_ROAD (warning) -------------------------------------------------------------------------------------

    private static LayoutAsset pad(String name, double x0, double y0, double x1, double y1) {
        return Asset.of(null, "STORAGE_PAD", name, rect(x0, y0, x1, y1))
                .size(x1 - x0, y1 - y0)
                .build();
    }

    @Test
    void blockOnARoadWarnsButStillSaves() throws SQLException {
        UUID road = stored("RD-1", "ROAD_SEGMENT", "Road 1", LayoutTestSupport.localRect(2000, 100, 2200, 112));

        var report = check(pad("Pad 7", 2050, 90, 2100, 140));

        assertFalse(report.hasErrors());
        var warning = report.warnings().get(0);
        assertEquals("ON_ROAD", warning.code());
        assertEquals("Pad 7 sits on road Road 1", warning.message());
        assertEquals(List.of(road.toString()), warning.assetIds());
    }

    @Test
    void blockBesideARoadHasNoWarning() throws SQLException {
        stored("RD-1", "ROAD_SEGMENT", "Road 1", LayoutTestSupport.localRect(2000, 100, 2200, 112));

        assertTrue(check(pad("Pad 7", 2050, 120, 2100, 160)).issues().isEmpty());
    }

    // ---- The single-asset gate ---------------------------------------------------------------------------------

    @Test
    void saveWithAnErrorIsRefusedWithEveryErrorListed() throws SQLException {
        stored("WH-B", "BUILDING", "Warehouse B", LayoutTestSupport.localRect(1000, 100, 1100, 140));

        var refused = assertThrows(
                RuleViolationException.class,
                () -> validation.requireSavable(draft, warehouse("Warehouse A", 1090, 100, 1190, 140)));

        assertEquals(1, refused.violations().size());
        assertEquals("OVERLAP", refused.violations().get(0).code());
        assertEquals("/geometry", refused.violations().get(0).pointer());
    }

    @Test
    void saveWithOnlyWarningsGoesThroughAndReturnsThem() throws SQLException {
        stored("RD-1", "ROAD_SEGMENT", "Road 1", LayoutTestSupport.localRect(2000, 100, 2200, 112));

        var warnings = validation.requireSavable(draft, pad("Pad 7", 2050, 90, 2100, 140));

        assertEquals(
                List.of("ON_ROAD"), warnings.stream().map(ValidationIssue::code).toList());
    }

    @Test
    void aDraftIsRequiredToSaveAnything() throws SQLException {
        UUID published;
        try (Connection c = dataSource.getConnection()) {
            published = LayoutTestSupport.version(c, 2, "PUBLISHED");
        }
        var refused = assertThrows(
                ConflictException.class,
                () -> validation.asset(published, warehouse("Warehouse A", 1000, 100, 1100, 140)));
        assertEquals("Only a draft can be changed", refused.getMessage());
    }
}
