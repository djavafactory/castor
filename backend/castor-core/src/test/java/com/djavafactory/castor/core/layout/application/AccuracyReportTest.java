package com.djavafactory.castor.core.layout.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.geometry.domain.LonLat;
import com.djavafactory.castor.core.geometry.domain.Measurements;
import com.djavafactory.castor.core.geometry.domain.QuayFrame;
import com.djavafactory.castor.core.layout.domain.AssetHistoryEntry;
import com.djavafactory.castor.core.layout.domain.LayoutAsset;
import com.djavafactory.castor.core.layout.domain.LayoutRepository;
import com.djavafactory.castor.core.layout.domain.LayoutStatus;
import com.djavafactory.castor.core.layout.domain.LayoutVersion;
import com.djavafactory.castor.core.layout.domain.LayoutView;
import com.djavafactory.castor.core.layout.domain.VersionSummary;
import com.djavafactory.castor.core.port.domain.AccuracyClass;
import com.djavafactory.castor.core.port.domain.BoundingBox;
import com.djavafactory.castor.core.port.domain.ControlPoint;
import com.djavafactory.castor.core.port.domain.DataSourceInfo;
import com.djavafactory.castor.core.port.domain.Port;
import com.djavafactory.castor.core.port.domain.PortRepository;
import com.djavafactory.castor.core.port.domain.PortSummary;
import com.djavafactory.castor.core.port.domain.ReferenceKind;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;
import com.djavafactory.castor.core.support.ShapeFixtures;
import com.djavafactory.castor.core.support.ShapeFixtures.Asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DESIGNER-11 part 1: the residual and round trip maths and the accuracy counts, with fake repositories so this runs
 * without a database. The distance comparison itself is proven against PostGIS by {@link AccuracyReportIT} (the
 * class name here is loosely coupled, but the fake measurer below mirrors {@code Measurements} exactly on a plane).
 */
class AccuracyReportTest {

    private static final QuayFrame FRAME = new QuayFrame(101.3066304, 2.9534057, -120.299);

    private final List<LayoutAsset> stored = new ArrayList<>();
    private List<ControlPoint> controlPoints = List.of();
    /** When set, round trips move the point by this many local metres along +X, to test a non-zero round trip. */
    private double roundTripDriftM;

    private final LayoutVersion version = new LayoutVersion(
            UUID.randomUUID(),
            "westports",
            1,
            LayoutStatus.PUBLISHED,
            null,
            "alice",
            Instant.now(),
            "alice",
            Instant.now(),
            null,
            null,
            "alice",
            Instant.now(),
            0);

    private final Port port = new Port(
            1,
            "westports",
            "Westports Malaysia",
            "Port Klang",
            "Asia/Kuala_Lumpur",
            new LonLat(FRAME.lon0(), FRAME.lat0()),
            3380,
            FRAME.rotDeg(),
            null,
            null,
            null,
            true,
            null);

    /** A control point whose stored WGS84 position is the given local point; expected local may differ. */
    private ControlPoint controlPoint(String code, double actualX, double actualY, double expectedX, double expectedY) {
        return new ControlPoint(code, code + " name", FRAME.toLonLat(actualX, actualY), expectedX, expectedY);
    }

    private AccuracyReport report() {
        return new AccuracyReport(layouts(), ports(), measurements());
    }

    private LayoutRepository layouts() {
        return new LayoutRepository() {
            @Override
            public Optional<LayoutVersion> findVersion(UUID layoutId) {
                return layoutId.equals(version.id()) ? Optional.of(version) : Optional.empty();
            }

            @Override
            public LayoutView load(LayoutVersion v) {
                return new LayoutView(v, stored, Map.of(), "x");
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
            public Optional<LayoutView> loadAsset(LayoutVersion v, UUID assetId) {
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

            @Override
            public void markRestored(UUID draftId, UUID sourceVersionId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LayoutVersion publish(LayoutVersion draft, String publishedBy, String note, String reviewedBy) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<VersionSummary> listVersions(String portCode, Collection<LayoutStatus> statuses, int limit) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<AssetHistoryEntry> assetHistory(UUID assetId, Collection<LayoutStatus> statuses) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean assetExists(UUID assetId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int countAssets(UUID layoutId) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private PortRepository ports() {
        return new PortRepository() {
            @Override
            public List<PortSummary> findAll() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Optional<Port> findByCode(String code) {
                return code.equals(port.code()) ? Optional.of(port) : Optional.empty();
            }

            @Override
            public List<DataSourceInfo> findDataSources() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int countPublishedBerths(Port p) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String controlPointsGeoJson(long portId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ControlPoint> controlPoints(long portId) {
                return controlPoints;
            }

            @Override
            public String referenceFeaturesGeoJson(long portId, Set<ReferenceKind> kinds, BoundingBox box) {
                throw new UnsupportedOperationException();
            }
        };
    }

    /** Measures exactly like PostGIS would on this plane: both points through the same local tangent plane. */
    private Measurements measurements() {
        return new Measurements() {
            @Override
            public double lengthM(List<LonLat> line, int measureSrid) {
                throw new UnsupportedOperationException();
            }

            @Override
            public double areaM2(List<LonLat> ring, int measureSrid) {
                throw new UnsupportedOperationException();
            }

            @Override
            public double perimeterM(List<LonLat> ring, int measureSrid) {
                throw new UnsupportedOperationException();
            }

            @Override
            public double distanceM(LonLat a, LonLat b, int measureSrid) {
                var pa = FRAME.toLocal(a);
                var pb = FRAME.toLocal(b);
                return Math.hypot(pa.x() - pb.x(), pa.y() - pb.y());
            }

            @Override
            public LonLat roundTrip(LonLat p, int measureSrid) {
                if (roundTripDriftM == 0) return p;
                var local = FRAME.toLocal(p);
                return FRAME.toLonLat(local.x() + roundTripDriftM, local.y());
            }
        };
    }

    @Test
    void residualIsTheDistanceBetweenTheExpectedLocalPointAndTheStoredOne() {
        controlPoints = List.of(controlPoint("CP-1", 100, 200, 103, 204));

        var result = report().execute(version.id());

        assertEquals(1, result.controlPoints().size());
        var cp = result.controlPoints().get(0);
        assertEquals("CP-1", cp.code());
        assertEquals(5.0, cp.residualM(), 1e-6); // 3-4-5 triangle
        assertEquals(5.0, result.maxResidualM(), 1e-6);
    }

    @Test
    void roundTripIsZeroWhenTheProjectionIsLossless() {
        controlPoints = List.of(controlPoint("CP-1", 100, 200, 100, 200));

        var result = report().execute(version.id());

        assertEquals(0.0, result.controlPoints().get(0).roundTripMm(), 1e-6);
        assertEquals(0.0, result.maxRoundTripMm(), 1e-6);
    }

    @Test
    void roundTripIsReportedInMillimetresAndTheLargestOneWins() {
        roundTripDriftM = 0.0005; // half a millimetre
        controlPoints = List.of(controlPoint("CP-1", 0, 0, 0, 0), controlPoint("CP-2", 500, 0, 500, 0));

        var result = report().execute(version.id());

        assertEquals(0.5, result.maxRoundTripMm(), 1e-6);
        assertTrue(result.controlPoints().stream().allMatch(cp -> cp.roundTripMm() > 0));
    }

    @Test
    void countsAssetsByTheirProvenanceAccuracyAndTheApproximateCount() {
        stored.add(approximateAsset("A"));
        stored.add(approximateAsset("B"));
        stored.add(ShapeFixtures.Asset.of(UUID.randomUUID(), "WAREHOUSE", "C", ShapeFixtures.rect(0, 0, 10, 10))
                .source("SURVEY", "SURVEYED")
                .build());

        var result = report().execute(version.id());

        assertEquals(2, result.countsByAccuracy().get(AccuracyClass.APPROXIMATE));
        assertEquals(1, result.countsByAccuracy().get(AccuracyClass.SURVEYED));
        assertEquals(0, result.countsByAccuracy().get(AccuracyClass.VERIFIED));
        assertEquals(0, result.countsByAccuracy().get(AccuracyClass.OPERATOR));
        assertEquals(2, result.approximateCount());
    }

    @Test
    void emptyLayoutHasZeroMaximaAndEveryAccuracyClassAtZero() {
        var result = report().execute(version.id());

        assertEquals(0.0, result.maxResidualM());
        assertEquals(0.0, result.maxRoundTripMm());
        assertTrue(result.controlPoints().isEmpty());
        for (AccuracyClass a : AccuracyClass.values()) {
            assertEquals(0, result.countsByAccuracy().get(a));
        }
        assertEquals(0, result.approximateCount());
    }

    @Test
    void unknownLayoutIsNotFound() {
        assertThrows(ResourceNotFoundException.class, () -> report().execute(UUID.randomUUID()));
    }

    private static LayoutAsset approximateAsset(String name) {
        return Asset.of(UUID.randomUUID(), "WAREHOUSE", name, ShapeFixtures.rect(0, 0, 10, 10))
                .source("SYNTHETIC", "APPROXIMATE")
                .build();
    }
}
