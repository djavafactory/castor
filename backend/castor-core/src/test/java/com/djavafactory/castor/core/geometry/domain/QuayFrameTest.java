package com.djavafactory.castor.core.geometry.domain;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.shared.domain.RuleViolationException;

import static com.djavafactory.castor.core.geometry.domain.CoordinateFixtures.metresApart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Backend half of the coordinate accuracy suite; the frontend half is lib/geo/geo.test.ts on the same fixtures. */
class QuayFrameTest {

    private final CoordinateFixtures fx = new CoordinateFixtures();
    private final QuayFrame frame = fx.frame;

    @Test
    void controlPointsConvertWithinTenCentimetres() {
        for (var p : fx.controlPoints) {
            LocalPoint l = frame.toLocal(p.lonLat());
            double err = Math.hypot(l.x() - p.local().x(), l.y() - p.local().y());
            assertTrue(err < fx.controlPointM, p.name() + " off by " + err);
        }
    }

    @Test
    void sharedPointsMatchTheFrontendWithinOneCentimetre() {
        assertEquals(20, fx.points.size());
        for (var p : fx.points) {
            LocalPoint l = frame.toLocal(p.lonLat());
            assertTrue(Math.hypot(l.x() - p.local().x(), l.y() - p.local().y()) < fx.backendVsFrontendM);
        }
    }

    @Test
    void wgs84ToLocalAndBackIsBelowOneMillimetre() {
        for (var p : fx.points) {
            LonLat back = frame.toLonLat(frame.toLocal(p.lonLat()));
            assertTrue(metresApart(back, p.lonLat()) < fx.roundTripM);
        }
    }

    @Test
    void rectanglesMatchTheSharedCornersWithinOneCentimetre() {
        assertEquals(5, fx.rectangles.size());
        for (var r : fx.rectangles) {
            var corners = QuayShapes.rect(frame, r.anchor(), r.heading(), r.length(), r.width());
            assertEquals(4, corners.size());
            for (int i = 0; i < 4; i++) {
                assertTrue(metresApart(corners.get(i), r.corners().get(i)) < fx.backendVsFrontendM);
            }
            assertEquals(r.rotation(), frame.headingToRotation(r.heading()), 1e-9);
        }
    }

    @Test
    void headingsFollowTheSharedTable() {
        for (var h : fx.headings) {
            assertEquals(h.rotation(), frame.headingToRotation(h.heading()), 1e-9);
            assertEquals(h.heading(), frame.rotationToHeading(h.rotation()), 1e-9);
        }
        assertEquals(210.299, frame.rotationToHeading(0), 1e-3);
        assertEquals(120.299, frame.rotationToHeading(90), 1e-3);
    }

    @Test
    void headingRangesAreNormalised() {
        double r = frame.headingToRotation(0);
        assertTrue(r > -180 && r <= 180);
        double h = frame.rotationToHeading(-500);
        assertTrue(h >= 0 && h < 360);
    }

    @Test
    void rectangleHasTheRequestedSidesAndArea() {
        var origin = new LonLat(frame.lon0(), frame.lat0());
        var ring = QuayShapes.rect(frame, origin, 210.3, 200, 40);

        assertEquals(200, LocalMetrics.distance(frame, ring.get(0), ring.get(1)), 1e-6);
        assertEquals(40, LocalMetrics.distance(frame, ring.get(1), ring.get(2)), 1e-6);
        assertEquals(8000, LocalMetrics.area(frame, ring), 1e-5);
        assertEquals(480, LocalMetrics.perimeter(frame, ring), 1e-5);
    }

    @Test
    void circleHas48PointsAtTheRadius() {
        var origin = new LonLat(frame.lon0(), frame.lat0());
        var ring = QuayShapes.circle(frame, origin, 25);

        assertEquals(48, ring.size());
        for (var p : ring) {
            assertEquals(25, LocalMetrics.distance(frame, origin, p), 1e-6);
        }
        assertEquals(12, QuayShapes.circle(frame, origin, 25, 12).size());
    }

    @Test
    void shapesRejectBadSizes() {
        var origin = new LonLat(frame.lon0(), frame.lat0());
        assertThrows(RuleViolationException.class, () -> QuayShapes.rect(frame, origin, 0, 0, 5));
        assertThrows(RuleViolationException.class, () -> QuayShapes.rect(frame, origin, 0, 5, -1));
        assertThrows(RuleViolationException.class, () -> QuayShapes.circle(frame, origin, 0));
        assertThrows(RuleViolationException.class, () -> QuayShapes.circle(frame, origin, 5, 2));
    }

    @Test
    void roundingKeepsSevenDecimalsAndReportsTheLargestMoveInMillimetres() {
        assertEquals(101.3066304, CoordinateRounding.round7(101.30663041234), 1e-12);

        var result = CoordinateRounding.round(
                List.of(new LonLat(101.30663041, 2.95340570), new LonLat(101.30663044, 2.95340573)));

        assertEquals(101.3066304, result.points().get(0).lon(), 1e-12);
        assertEquals(2.9534057, result.points().get(1).lat(), 1e-12);
        // 4e-8 degrees of longitude is about 4.4 mm
        assertTrue(result.maxAdjustmentMm() > 3 && result.maxAdjustmentMm() < 6, "was " + result.maxAdjustmentMm());
        assertEquals(
                0, CoordinateRounding.round(List.of(new LonLat(101.3, 2.9))).maxAdjustmentMm(), 1e-6);
    }
}
