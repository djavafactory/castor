package com.djavafactory.castor.core.layout.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import com.djavafactory.castor.core.geometry.domain.LonLat;
import com.djavafactory.castor.core.geometry.domain.QuayFrame;
import com.djavafactory.castor.core.shared.domain.RuleViolation;
import com.djavafactory.castor.core.shared.domain.RuleViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules every feature passes before it is stored (seed, import, designer). */
class LayoutFeatureValidatorTest {

    private static final QuayFrame FRAME = new QuayFrame(101.3066304, 2.9534057, -120.299);
    private static final LonLat ANCHOR = new LonLat(101.3100000, 2.9500000);
    private static final AssetType.Sizes NONE = new AssetType.Sizes(null, null, null, null, null);

    private static AssetType type(String code, String kind, boolean mobile) {
        return new AssetType(
                code,
                code,
                "STORAGE",
                "STORAGE",
                kind,
                "PLACE",
                new AssetType.Flags(mobile, true, false, false, false, false),
                NONE,
                new AssetType.Sizes(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                new AssetType.Sizes(
                        BigDecimal.valueOf(500),
                        BigDecimal.valueOf(500),
                        BigDecimal.valueOf(100),
                        BigDecimal.valueOf(50),
                        null),
                null,
                null,
                Map.of(),
                code.toLowerCase(),
                1,
                "#112233",
                "hint",
                1);
    }

    private static final Map<String, AssetType> TYPES = Map.of(
            "BUILDING", type("BUILDING", "RECT", false),
            "BERTH", type("BERTH", "QUAY_SEGMENT", false),
            "FORKLIFT", type("FORKLIFT", "POINT", true),
            "SILO", type("SILO", "CIRCLE", false));

    private final Geometry extent =
            Footprints.FACTORY.toGeometry(new org.locationtech.jts.geom.Envelope(101.26, 101.36, 2.90, 3.02));
    private final LayoutFeatureValidator validator = new LayoutFeatureValidator(TYPES, FRAME, extent);

    private static LayoutAsset building(String code, Geometry geom, BigDecimal length) {
        return new LayoutAsset(
                null,
                code,
                "BUILDING",
                "Hall",
                geom,
                ANCHOR,
                BigDecimal.valueOf(10),
                length,
                BigDecimal.valueOf(12),
                BigDecimal.valueOf(6),
                null,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                1,
                null,
                null,
                null,
                new LayoutAsset.Provenance("SYNTHETIC", "APPROXIMATE", null, null),
                null,
                AssetDetails.NONE,
                List.of(),
                0);
    }

    private static Geometry hall() {
        return Footprints.rect(FRAME, ANCHOR, 10, 30, 12);
    }

    private Set<String> codes(FeatureDraft... drafts) {
        return validator.validate(List.of(drafts)).stream()
                .map(RuleViolation::code)
                .collect(Collectors.toSet());
    }

    private static FeatureDraft draft(LayoutAsset a, FeatureDraft.DraftLink... links) {
        return new FeatureDraft(a, List.of(links));
    }

    @Test
    void aCorrectFeatureHasNoViolations() {
        assertEquals(Set.of(), codes(draft(building("WH1", hall(), BigDecimal.valueOf(30)))));
    }

    @Test
    void badCodeIsRejected() {
        assertTrue(
                codes(draft(building("wh 1", hall(), BigDecimal.valueOf(30)))).contains("CODE_FORMAT"));
        assertTrue(codes(draft(building("X".repeat(41), hall(), BigDecimal.valueOf(30))))
                .contains("CODE_FORMAT"));
    }

    @Test
    void duplicateCodesAreRejected() {
        var a = draft(building("WH1", hall(), BigDecimal.valueOf(30)));
        assertTrue(codes(a, a).contains("DUPLICATE_CODE"));
    }

    @Test
    void geometryKindMustMatchTheType() {
        var line = Footprints.FACTORY.createLineString(
                new Coordinate[] {new Coordinate(101.31, 2.95), new Coordinate(101.311, 2.95)});
        assertTrue(codes(draft(building("WH1", line, BigDecimal.valueOf(30)))).contains("GEOMETRY_KIND"));
    }

    @Test
    void geometryOutsideTheExtentIsRejected() {
        var far = Footprints.rect(FRAME, new LonLat(103.0, 2.95), 10, 30, 12);
        assertTrue(codes(draft(building("WH1", far, BigDecimal.valueOf(30)))).contains("OUTSIDE_EXTENT"));
    }

    @Test
    void footprintThatDoesNotMatchItsSizeIsRejected() {
        // stored 30 m long, fields say 40 m
        assertTrue(codes(draft(building("WH1", hall(), BigDecimal.valueOf(40)))).contains("FOOTPRINT_MISMATCH"));
    }

    @Test
    void sizesOutsideTheTypeLimitsAreRejected() {
        var huge = Footprints.rect(FRAME, ANCHOR, 10, 900, 12);
        assertTrue(codes(draft(building("WH1", huge, BigDecimal.valueOf(900)))).contains("SIZE_RANGE"));
    }

    @Test
    void mobileTypesNeedMobileDetails() {
        var a = new LayoutAsset(
                null,
                "FL1",
                "FORKLIFT",
                "Forklift",
                Footprints.rect(FRAME, ANCHOR, 0, 4, 2),
                ANCHOR,
                BigDecimal.ZERO,
                BigDecimal.valueOf(4),
                BigDecimal.valueOf(2),
                null,
                null,
                null,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                1,
                null,
                null,
                null,
                new LayoutAsset.Provenance("SYNTHETIC", "APPROXIMATE", null, null),
                null,
                AssetDetails.NONE,
                List.of(),
                0);
        assertTrue(codes(draft(a)).contains("DETAIL_REQUIRED"));
    }

    @Test
    void linksMustResolveAndMayNotPointAtThemselves() {
        var a = building("WH1", hall(), BigDecimal.valueOf(30));
        var found = codes(draft(
                a,
                new FeatureDraft.DraftLink("SERVES", "NOWHERE"),
                new FeatureDraft.DraftLink("SERVES", "WH1"),
                new FeatureDraft.DraftLink("OWNS", "WH1")));
        assertTrue(found.containsAll(Set.of("LINK_TARGET", "LINK_SELF", "LINK_KIND")), found.toString());
    }

    @Test
    void unknownTypeIsRejected() {
        var a = building("WH1", hall(), BigDecimal.valueOf(30));
        var odd = new LayoutAsset(
                null,
                a.code(),
                "NO_SUCH",
                a.name(),
                a.geom(),
                a.anchor(),
                a.headingDeg(),
                a.lengthM(),
                a.widthM(),
                a.heightM(),
                null,
                null,
                a.elevationM(),
                a.clearanceM(),
                1,
                null,
                null,
                null,
                a.provenance(),
                null,
                AssetDetails.NONE,
                List.of(),
                0);
        assertTrue(codes(draft(odd)).contains("UNKNOWN_TYPE"));
    }

    @Test
    void requireValidThrowsWithEveryViolation() {
        var e = assertThrows(
                RuleViolationException.class,
                () -> validator.requireValid(List.of(draft(building("bad code", hall(), BigDecimal.valueOf(40))))));
        assertTrue(e.violations().size() >= 2);
        assertEquals("bad code", e.violations().get(0).assetId());
    }
}
