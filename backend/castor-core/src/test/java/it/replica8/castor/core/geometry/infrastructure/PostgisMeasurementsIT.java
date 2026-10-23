package it.replica8.castor.core.geometry.infrastructure;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import it.replica8.castor.core.geometry.domain.CoordinateFixtures;
import it.replica8.castor.core.geometry.domain.LocalMetrics;
import it.replica8.castor.core.geometry.domain.LonLat;
import it.replica8.castor.core.geometry.domain.Measurements;
import it.replica8.castor.core.geometry.domain.QuayShapes;

import static it.replica8.castor.core.geometry.domain.CoordinateFixtures.metresApart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PostGIS half of the accuracy suite: ST_Transform to EPSG:3380 round-trips and agrees with the local frame. */
@QuarkusTest
class PostgisMeasurementsIT {

    private static final int SRID = Measurements.DEFAULT_MEASURE_SRID;

    @Inject
    PostgisMeasurements measurements;

    private final CoordinateFixtures fx = new CoordinateFixtures();

    @Test
    void projectionRoundTripIsBelowOneMillimetre() {
        for (var p : fx.points) {
            LonLat back = measurements.roundTrip(p.lonLat(), SRID);
            assertTrue(metresApart(back, p.lonLat()) < fx.roundTripM, "off by " + metresApart(back, p.lonLat()));
        }
        for (var p : fx.controlPoints) {
            assertTrue(metresApart(measurements.roundTrip(p.lonLat(), SRID), p.lonLat()) < fx.roundTripM);
        }
    }

    @Test
    void rectangleMeasuresAgreeWithTheLocalFrameWithinTheProjectionScale() {
        // The Selangor grid scale differs from a tangent plane by well under 0.1 % over a few hundred metres.
        for (var r : fx.rectangles) {
            var ring = QuayShapes.rect(fx.frame, r.anchor(), r.heading(), r.length(), r.width());

            assertEquals(r.length() * 2 + r.width() * 2, measurements.perimeterM(ring, SRID), r.length() * 0.002);
            assertEquals(r.length() * r.width(), measurements.areaM2(ring, SRID), r.length() * r.width() * 0.002);
            assertEquals(
                    LocalMetrics.perimeter(fx.frame, ring), measurements.perimeterM(ring, SRID), r.length() * 0.002);
        }
    }

    @Test
    void lengthAndDistanceUseMetricCoordinates() {
        var a = fx.controlPoints.get(0).lonLat();
        var b = fx.controlPoints.get(1).lonLat();
        double local = LocalMetrics.distance(fx.frame, a, b);

        assertEquals(local, measurements.distanceM(a, b, SRID), local * 0.002);
        assertEquals(local, measurements.lengthM(java.util.List.of(a, b), SRID), local * 0.002);
    }
}
