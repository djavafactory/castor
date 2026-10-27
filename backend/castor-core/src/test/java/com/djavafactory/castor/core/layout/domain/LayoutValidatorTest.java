package com.djavafactory.castor.core.layout.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.layout.domain.AssetType.Sizes;
import com.djavafactory.castor.core.layout.domain.PlacementFacts.Berth;
import com.djavafactory.castor.core.layout.domain.PlacementFacts.Candidate;
import com.djavafactory.castor.core.layout.domain.PlacementFacts.Contact;
import com.djavafactory.castor.core.support.LayoutTestSupport;
import com.djavafactory.castor.core.support.ShapeFixtures;
import com.djavafactory.castor.core.support.ShapeFixtures.Asset;

import static com.djavafactory.castor.core.support.ShapeFixtures.line;
import static com.djavafactory.castor.core.support.ShapeFixtures.path;
import static com.djavafactory.castor.core.support.ShapeFixtures.polygon;
import static com.djavafactory.castor.core.support.ShapeFixtures.rect;
import static com.djavafactory.castor.core.support.ShapeFixtures.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that need no database: shape, size, source and the water and land edges, plus the berth rules and the order
 * in which one shape's placement is checked. What PostGIS answers (shapes that meet) is replaced by a fake here and
 * tested for real in LayoutValidatorIT. Quay frame: land is y from 0 to 1400, the quay line is y = 0, water is y &lt; 0.
 */
class LayoutValidatorTest {

    private static final UUID NEW = null;

    private static final Map<String, AssetType> TYPES = Map.of(
            "WAREHOUSE", type("WAREHOUSE", "RECT", "PLACE", "S"),
            "WAREHOUSE_POLY", type("WAREHOUSE_POLY", "POLYGON", "DRAW", "S"),
            "CONVEYOR", type("CONVEYOR", "LINE", "DRAW", ""),
            "BOLLARD", type("BOLLARD", "POINT", "PLACE", "W"),
            "BERTH", type("BERTH", "QUAY_SEGMENT", "PLACE", "W"),
            "QUAY_CRANE_RAIL", type("QUAY_CRANE_RAIL", "POINT", "PLACE", "B"),
            "STORAGE_PAD", type("STORAGE_PAD", "RECT", "PLACE", "S"),
            "LIMITED",
                    ShapeFixtures.withMax(
                            type("LIMITED", "RECT", "PLACE", "S"),
                            new Sizes(
                                    BigDecimal.valueOf(100),
                                    BigDecimal.valueOf(50),
                                    BigDecimal.valueOf(20),
                                    null,
                                    null)));

    private final Site site = new Site(
            LayoutTestSupport.FRAME,
            rect(-4000, -500, 7000, 1500),
            rect(-3500, 0, 6000, 1400),
            line(-3500, 0, 6000, 0));

    private final FakeFacts facts = new FakeFacts();
    private final List<LayoutAsset> stored = new ArrayList<>();

    private final LayoutVersion draft = new LayoutVersion(
            UUID.randomUUID(),
            "westports",
            2,
            LayoutStatus.DRAFT,
            null,
            "alice",
            Instant.now(),
            null,
            null,
            null,
            null,
            "alice",
            Instant.now(),
            0);

    private LayoutValidator validator(String... untraceable) {
        LayoutRepository repository = new LayoutRepository() {
            @Override
            public LayoutView load(LayoutVersion version) {
                return new LayoutView(version, stored, Map.of(), "x");
            }

            @Override
            public List<LayoutVersion> findLatestVersions() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<LayoutVersion> findPublished(String portCode) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<LayoutVersion> findDraft(String portCode) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<LayoutVersion> findVersion(UUID layoutId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<LayoutView> loadAsset(LayoutVersion version, UUID assetId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LayoutVersion createDraft(String portCode, LayoutVersion basedOn, String createdBy) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LayoutVersion discard(LayoutVersion draft) {
                throw new UnsupportedOperationException();
            }
        };
        return new LayoutValidator(repository, TYPES, site, Set.of(untraceable), facts);
    }

    private ValidationReport check(LayoutAsset candidate) {
        return validator("GOOGLE").validateAsset(draft, candidate);
    }

    private static Asset warehouse(Double x0, Double y0, Double x1, Double y1) {
        return Asset.of(NEW, "WAREHOUSE", "Warehouse A", rect(x0, y0, x1, y1)).size(x1 - x0, y1 - y0);
    }

    private static List<String> codes(ValidationReport report) {
        return report.issues().stream().map(ValidationIssue::code).toList();
    }

    private static ValidationIssue only(ValidationReport report, String code) {
        var found = report.issues().stream().filter(i -> i.code().equals(code)).toList();
        assertEquals(1, found.size(), "expected exactly one " + code + " in " + codes(report));
        return found.get(0);
    }

    // ---- GEOM_INVALID ------------------------------------------------------------------------------------------

    @Test
    void geometryInvalidWhenAHoleLiesOutsideTheOutline() {
        var shell = "POLYGON((%s), (%s))";
        var outer = polygon(1000, 100, 1100, 100, 1100, 200, 1000, 200).toText();
        var ring = outer.substring(outer.indexOf("((") + 2, outer.indexOf("))"));
        var far = polygon(1500, 100, 1520, 100, 1520, 120, 1500, 120).toText();
        var hole = far.substring(far.indexOf("((") + 2, far.indexOf("))"));
        var invalid = ShapeFixtures.wkt(shell.formatted(ring, hole));

        var report = check(Asset.of(NEW, "WAREHOUSE_POLY", "Hall", invalid).build());

        assertEquals("Hall has an invalid shape", only(report, "GEOM_INVALID").message());
        assertTrue(report.hasErrors());
    }

    @Test
    void geometryValidPasses() {
        var report = check(Asset.of(NEW, "WAREHOUSE_POLY", "Hall", rect(1000, 100, 1100, 200))
                .build());
        assertFalse(codes(report).contains("GEOM_INVALID"));
    }

    // ---- SELF_INTERSECTION -------------------------------------------------------------------------------------

    @Test
    void selfIntersectionNamesTheCrossingEdgesForOneShape() {
        var bowTie = polygon(1000, 100, 1100, 200, 1100, 100, 1000, 200);

        var issue = only(check(Asset.of(NEW, "WAREHOUSE_POLY", "Hall", bowTie).build()), "SELF_INTERSECTION");

        assertEquals("The outline crosses itself (edges 1 and 3)", issue.message());
    }

    @Test
    void selfIntersectionInTheLayoutListNamesTheShape() {
        var id = UUID.randomUUID();
        stored.add(Asset.of(id, "WAREHOUSE_POLY", "Hall", polygon(1000, 100, 1100, 200, 1100, 100, 1000, 200))
                .build());

        var report = validator().validateLayout(draft);

        var issue = only(report, "SELF_INTERSECTION");
        assertEquals("Hall has a self-intersecting outline and cannot be published", issue.message());
        assertEquals(List.of(id.toString()), issue.assetIds());
    }

    @Test
    void simpleLShapeIsNotSelfIntersecting() {
        var ell = polygon(1000, 100, 1100, 100, 1100, 130, 1030, 130, 1030, 200, 1000, 200);
        assertFalse(codes(check(Asset.of(NEW, "WAREHOUSE_POLY", "Hall", ell).build()))
                .contains("SELF_INTERSECTION"));
    }

    // ---- TOO_FEW_POINTS ----------------------------------------------------------------------------------------

    @Test
    void polygonWithTwoDistinctCornersNeedsMore() {
        var flat = polygon(1000, 100, 1100, 100, 1100, 100);

        var report = check(Asset.of(NEW, "WAREHOUSE_POLY", "Hall", flat).build());

        assertEquals(
                "Hall needs at least three corners",
                only(report, "TOO_FEW_POINTS").message());
    }

    @Test
    void lineWithOnePointNeedsTwo() {
        var dot = path(1000, 100, 1000, 100);

        var report = check(Asset.of(NEW, "CONVEYOR", "Belt", dot).lineWidth(2.0).build());

        assertEquals(
                "Belt needs at least two points", only(report, "TOO_FEW_POINTS").message());
    }

    @Test
    void triangleAndTwoPointLineHaveEnough() {
        var triangle = polygon(1000, 100, 1100, 100, 1050, 200);
        assertFalse(
                codes(check(Asset.of(NEW, "WAREHOUSE_POLY", "Hall", triangle).build()))
                        .contains("TOO_FEW_POINTS"));
        var belt = Asset.of(NEW, "CONVEYOR", "Belt", path(1000, 100, 1100, 100))
                .lineWidth(2.0)
                .build();
        assertFalse(codes(check(belt)).contains("TOO_FEW_POINTS"));
    }

    // ---- TOO_SMALL ---------------------------------------------------------------------------------------------

    @Test
    void drawnPolygonUnderFourSquareMetresIsTooSmall() {
        var report = check(Asset.of(NEW, "WAREHOUSE_POLY", "Hall", rect(1000, 100, 1001.5, 101.5))
                .build());
        assertEquals(
                "The outline is smaller than 4 m2", only(report, "TOO_SMALL").message());
    }

    @Test
    void drawnPolygonOfFourSquareMetresOrMoreAndPlacedSmallEquipmentPass() {
        assertFalse(codes(check(Asset.of(NEW, "WAREHOUSE_POLY", "Hall", rect(1000, 100, 1003, 103))
                        .build()))
                .contains("TOO_SMALL"));
        var bollard = Asset.of(NEW, "BOLLARD", "Bollard", rect(1000, 100, 1001.2, 101.2))
                .size(1.2, 1.2);
        assertTrue(check(bollard.build()).issues().isEmpty());
    }

    // ---- WRONG_GEOMETRY_KIND -----------------------------------------------------------------------------------

    @Test
    void polygonForALineTypeIsTheWrongKind() {
        var report = check(Asset.of(NEW, "CONVEYOR", "Belt", rect(1000, 100, 1100, 120))
                .lineWidth(2.0)
                .build());
        assertEquals(
                "Belt: geometry does not match type CONVEYOR",
                only(report, "WRONG_GEOMETRY_KIND").message());
    }

    @Test
    void lineForALineTypeIsTheRightKind() {
        var belt = Asset.of(NEW, "CONVEYOR", "Belt", path(1000, 100, 1100, 100))
                .lineWidth(2.0)
                .build();
        assertTrue(check(belt).issues().isEmpty());
    }

    // ---- OUT_OF_PORT -------------------------------------------------------------------------------------------

    @Test
    void shapeBeyondThePortExtentIsOutOfPort() {
        var report = check(warehouse(9000.0, 100.0, 9100.0, 140.0).build());
        assertEquals(
                "Warehouse A lies outside the port extent",
                only(report, "OUT_OF_PORT").message());
    }

    @Test
    void shapeInsideThePortExtentIsNotOutOfPort() {
        assertTrue(
                check(warehouse(1000.0, 100.0, 1100.0, 140.0).build()).issues().isEmpty());
    }

    // ---- ON_WATER and OUTSIDE_LAND -----------------------------------------------------------------------------

    @Test
    void shapeCrossingTheQuayLineIsOnWater() {
        var report = check(warehouse(1000.0, -10.0, 1100.0, 30.0).build());
        assertEquals(
                "Extends past the quay edge into the water",
                only(report, "ON_WATER").message());
    }

    @Test
    void shapeBehindTheQuayLineIsNotOnWater() {
        assertTrue(check(warehouse(1000.0, 5.0, 1100.0, 45.0).build()).issues().isEmpty());
    }

    @Test
    void typesThatMayExtendOverWaterAreNotChecked() {
        var bollard = Asset.of(NEW, "BOLLARD", "Bollard", rect(1000, -3, 1001.2, -1.8))
                .size(1.2, 1.2);
        assertTrue(check(bollard.build()).issues().isEmpty());
    }

    @Test
    void shapeInsideThePortButOutsideTheLandIsOutsideLand() {
        var report = check(warehouse(6200.0, 100.0, 6300.0, 140.0).build());
        assertEquals("Outside the land area", only(report, "OUTSIDE_LAND").message());
    }

    @Test
    void shapeOnTheLandIsNotOutsideLand() {
        assertFalse(
                codes(check(warehouse(5800.0, 100.0, 5900.0, 140.0).build())).contains("OUTSIDE_LAND"));
    }

    // ---- DIM_OUT_OF_RANGE --------------------------------------------------------------------------------------

    @Test
    void sizeBelowTheMinimumIsRefused() {
        var asset =
                Asset.of(NEW, "WAREHOUSE", "Hall", rect(1000, 100, 1100, 140)).size(0.2, 40.0);
        var report = check(asset.build());
        assertEquals(
                "Length: enter a number of at least 0.5",
                only(report, "DIM_OUT_OF_RANGE").message());
    }

    @Test
    void missingSizeIsAskedFor() {
        var asset =
                Asset.of(NEW, "WAREHOUSE", "Hall", rect(1000, 100, 1100, 140)).size(100.0, null);
        var report = check(asset.build());
        assertEquals(
                "Width: enter a number of at least 0.5",
                only(report, "DIM_OUT_OF_RANGE").message());
    }

    @Test
    void sizeAboveTheMaximumIsRefused() {
        var asset = Asset.of(NEW, "LIMITED", "Hall", rect(1000, 100, 1200, 140)).size(200.0, 40.0);
        var report = check(asset.build());
        assertEquals(
                "Length: enter a number of at most 100",
                only(report, "DIM_OUT_OF_RANGE").message());
    }

    @Test
    void sizeInsideTheLimitsPasses() {
        var asset = Asset.of(NEW, "LIMITED", "Hall", rect(1000, 100, 1100, 140))
                .size(100.0, 40.0)
                .height(20.0);
        assertTrue(check(asset.build()).issues().isEmpty());
    }

    // ---- UNKNOWN_TYPE and TRACE_SOURCE_NOT_ALLOWED -------------------------------------------------------------

    @Test
    void unknownTypeIsRefused() {
        var report = check(
                Asset.of(NEW, "SPACESHIP", "Rocket", rect(1000, 100, 1100, 140)).build());
        assertEquals(
                "Rocket: unknown asset type SPACESHIP",
                only(report, "UNKNOWN_TYPE").message());
    }

    @Test
    void knownTypePassesTheTypeCheck() {
        assertFalse(
                codes(check(warehouse(1000.0, 100.0, 1100.0, 140.0).build())).contains("UNKNOWN_TYPE"));
    }

    @Test
    void shapeFromGoogleMapsIsRefused() {
        var asset = warehouse(1000.0, 100.0, 1100.0, 140.0).source("GOOGLE", "APPROXIMATE");
        var report = check(asset.build());
        assertTrue(only(report, "TRACE_SOURCE_NOT_ALLOWED")
                .message()
                .startsWith("Warehouse A was traced from unlicensed imagery. Publishing is blocked"));
    }

    @Test
    void shapeFromAnAllowedSourcePasses() {
        var asset = warehouse(1000.0, 100.0, 1100.0, 140.0).source("SENTINEL2_EOX", "APPROXIMATE");
        assertFalse(codes(check(asset.build())).contains("TRACE_SOURCE_NOT_ALLOWED"));
    }

    // ---- PILE_SLOT_TOO_SMALL (warning) -------------------------------------------------------------------------

    private static AssetDetails pad(double bayPitch, double rowPitch) {
        var storagePad = new AssetDetails.StoragePad(
                1, 1, BigDecimal.valueOf(bayPitch), BigDecimal.valueOf(rowPitch), BigDecimal.TEN);
        return new AssetDetails(null, null, null, storagePad, null, null, null, null, null, null, null);
    }

    @Test
    void padSlotUnderTenMetresWarnsButDoesNotBlock() {
        var asset = Asset.of(NEW, "STORAGE_PAD", "Pad 7", rect(1000, 100, 1030, 120))
                .size(30.0, 20.0)
                .details(pad(8, 12.5));

        var report = check(asset.build());

        assertFalse(report.hasErrors());
        var warning = only(report, "PILE_SLOT_TOO_SMALL");
        assertEquals(IssueLevel.WARNING, warning.level());
        assertEquals("Pad 7: a 8 x 12.5 m slot is too small to hold a pile", warning.message());
    }

    @Test
    void padSlotOfTenMetresDoesNotWarn() {
        var asset = Asset.of(NEW, "STORAGE_PAD", "Pad 7", rect(1000, 100, 1030, 120))
                .size(30.0, 20.0)
                .details(pad(10, 10));
        assertTrue(check(asset.build()).issues().isEmpty());
    }

    // ---- BERTH_OVERLAP and CRANE_OFF_BERTH ---------------------------------------------------------------------

    private void berthAt(String name, double x0, double x1) {
        facts.berths.add(new Berth(UUID.randomUUID(), name, line(x0, 0, x1, 0)));
    }

    @Test
    void berthsSharingMoreThanFiveCentimetresOfQuayOverlap() {
        berthAt("Berth 1", 0, 200);
        var report = check(Asset.of(NEW, "BERTH", "Berth 2", line(150, 0, 350, 0))
                .size(200.0, null)
                .build());
        assertEquals(
                "Overlaps Berth 1 along the quay", only(report, "BERTH_OVERLAP").message());
    }

    @Test
    void berthsThatOnlyMeetDoNotOverlap() {
        berthAt("Berth 1", 0, 200);
        var report = check(Asset.of(NEW, "BERTH", "Berth 2", line(200, 0, 400, 0))
                .size(200.0, null)
                .build());
        assertTrue(report.issues().isEmpty());
    }

    @Test
    void railCraneAwayFromEveryBerthIsOffBerth() {
        berthAt("Berth 1", 0, 200);
        var crane = Asset.of(NEW, "QUAY_CRANE_RAIL", "Crane 1", rect(500, 2, 524, 18))
                .size(24.0, 16.0);
        var report = check(crane.build());
        assertEquals(
                "A rail-mounted quay crane must stand on a berth",
                only(report, "CRANE_OFF_BERTH").message());
    }

    @Test
    void railCraneFarInlandIsOffBerthEvenAlongTheBerth() {
        berthAt("Berth 1", 0, 200);
        var crane = Asset.of(NEW, "QUAY_CRANE_RAIL", "Crane 1", rect(50, 100, 74, 116))
                .size(24.0, 16.0);
        assertEquals(1, check(crane.build()).errors().size());
    }

    @Test
    void railCraneOnABerthPasses() {
        berthAt("Berth 1", 0, 200);
        var crane =
                Asset.of(NEW, "QUAY_CRANE_RAIL", "Crane 1", rect(50, 2, 74, 18)).size(24.0, 16.0);
        assertTrue(check(crane.build()).issues().isEmpty());
    }

    // ---- Order of the placement rules --------------------------------------------------------------------------

    @Test
    void firstFailingPlacementRuleIsTheOneReported() {
        // on the water AND overlapping a stored warehouse: ON_WATER comes first, the overlap is not mentioned
        facts.contacts.add(new Contact(NEW, UUID.randomUUID(), "Warehouse B", "WAREHOUSE", 500, true, List.of()));

        var report = check(warehouse(1000.0, -10.0, 1100.0, 30.0).build());

        assertEquals(List.of("ON_WATER"), codes(report));
    }

    @Test
    void shapeRulesComeBeforePlacementRules() {
        facts.contacts.add(new Contact(NEW, UUID.randomUUID(), "Warehouse B", "WAREHOUSE", 500, true, List.of()));

        var report = check(warehouse(9000.0, -10.0, 9100.0, 30.0).build());

        assertEquals(List.of("OUT_OF_PORT"), codes(report));
    }

    @Test
    void errorsComeBeforeWarnings() {
        var asset = Asset.of(NEW, "STORAGE_PAD", "Pad 7", rect(1000, -10, 1030, 20))
                .size(30.0, 30.0)
                .details(pad(8, 8));
        assertEquals(List.of("ON_WATER", "PILE_SLOT_TOO_SMALL"), codes(check(asset.build())));
    }

    // ---- The whole layout --------------------------------------------------------------------------------------

    @Test
    void layoutCountsShapesWithoutSurvey() {
        var one = UUID.randomUUID();
        var two = UUID.randomUUID();
        var surveyed = UUID.randomUUID();
        stored.add(warehouse(1000.0, 100.0, 1100.0, 140.0)
                .source("SYNTHETIC", "APPROXIMATE")
                .buildAs(one));
        stored.add(warehouse(1200.0, 100.0, 1300.0, 140.0)
                .source("SYNTHETIC", "APPROXIMATE")
                .buildAs(two));
        stored.add(warehouse(1400.0, 100.0, 1500.0, 140.0)
                .source("SURVEY", "SURVEYED")
                .buildAs(surveyed));

        var report = validator().validateLayout(draft);

        var warning = only(report, "APPROXIMATE_SOURCE");
        assertEquals("2 shapes have no survey", warning.message());
        assertEquals(Set.of(one.toString(), two.toString()), Set.copyOf(warning.assetIds()));
        assertFalse(report.hasErrors());
    }

    @Test
    void layoutReportsAnOverlapOnceWithBothAssets() {
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        stored.add(warehouse(1000.0, 100.0, 1100.0, 140.0).buildAs(a, "Warehouse A"));
        stored.add(warehouse(1050.0, 100.0, 1150.0, 140.0).buildAs(b, "Warehouse B"));
        facts.contacts.add(new Contact(a, b, "Warehouse B", "WAREHOUSE", 2000, true, List.of()));
        facts.contacts.add(new Contact(b, a, "Warehouse A", "WAREHOUSE", 2000, true, List.of()));

        var overlaps = validator().validateLayout(draft).errors();

        assertEquals(1, overlaps.size());
        assertEquals("OVERLAP", overlaps.get(0).code());
        assertEquals("Overlaps Warehouse B", overlaps.get(0).message());
        assertEquals(List.of(a.toString(), b.toString()), overlaps.get(0).assetIds());
    }

    /** Answers what PostGIS would: the contacts and berths a test sets up, for any candidate. */
    private static final class FakeFacts implements PlacementFacts {
        final List<Contact> contacts = new ArrayList<>();
        final List<Berth> berths = new ArrayList<>();

        @Override
        public List<Contact> contacts(UUID layoutVersionId, Candidate candidate) {
            return contacts;
        }

        @Override
        public List<Berth> berths(UUID layoutVersionId) {
            return berths;
        }
    }
}
