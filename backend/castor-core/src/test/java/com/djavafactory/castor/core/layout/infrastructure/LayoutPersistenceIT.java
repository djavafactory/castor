package com.djavafactory.castor.core.layout.infrastructure;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import com.djavafactory.castor.core.layout.domain.Footprints;
import com.djavafactory.castor.core.support.LayoutTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Hibernate mapping of the layout tables with spatial types (CORE-04 part 3). */
@QuarkusTest
class LayoutPersistenceIT {

    /** Nine decimals: far more than the 7 the API writes, so any rounding in the mapping layer shows up. */
    private static final Coordinate[] HALL = {
        new Coordinate(101.306630412, 2.953405712),
        new Coordinate(101.306730487, 2.953405791),
        new Coordinate(101.306730523, 2.953505634),
        new Coordinate(101.306630455, 2.953505678),
        new Coordinate(101.306630412, 2.953405712)
    };

    @Inject
    EntityManager em;

    @Inject
    DataSource dataSource;

    private UUID version;
    private UUID asset;

    @BeforeEach
    void setUp() throws SQLException {
        LayoutTestSupport.clear(dataSource);
        try (Connection c = dataSource.getConnection()) {
            version = LayoutTestSupport.version(c, 1, "DRAFT");
            asset = LayoutTestSupport.asset(c, "WH1", "BUILDING");
        }
    }

    @AfterEach
    void tearDown() {
        LayoutTestSupport.clear(dataSource);
    }

    private AssetRevisionEntity revision(UUID assetId, Geometry geom) {
        var r = new AssetRevisionEntity();
        r.id = new AssetRevisionId(assetId, version);
        r.name = "Hall";
        r.geom = geom;
        geom.setSRID(Footprints.SRID);
        var anchor = geom.getInteriorPoint();
        anchor.setSRID(Footprints.SRID);
        r.anchor = anchor;
        r.accuracyCode = "APPROXIMATE";
        r.sourceCode = "SYNTHETIC";
        r.createdBy = "test";
        r.updatedBy = "test";
        return r;
    }

    @Test
    void aPolygonSavedAndLoadedKeepsItsCoordinatesToOneNanodegree() {
        QuarkusTransaction.requiringNew()
                .run(() -> em.persist(revision(asset, Footprints.FACTORY.createPolygon(HALL))));

        var loaded = QuarkusTransaction.requiringNew()
                .call(() -> em.find(AssetRevisionEntity.class, new AssetRevisionId(asset, version)));

        Coordinate[] back = loaded.geom.getCoordinates();
        assertEquals(HALL.length, back.length);
        for (int i = 0; i < HALL.length; i++) {
            assertEquals(HALL[i].x, back[i].x, 1e-9);
            assertEquals(HALL[i].y, back[i].y, 1e-9);
        }
        assertEquals(4326, loaded.geom.getSRID());
        assertEquals(4326, loaded.anchor.getSRID());
    }

    @Test
    void hqlSpatialFunctionsWorkAgainstAssetRevision() {
        QuarkusTransaction.requiringNew()
                .run(() -> em.persist(revision(asset, Footprints.FACTORY.createPolygon(HALL))));
        Geometry around = box(101.3065, 2.9533, 101.3069, 2.9537);
        Geometry elsewhere = box(101.30, 2.90, 101.301, 2.901);
        Geometry touching = box(101.30672, 2.9534, 101.3075, 2.9536);

        QuarkusTransaction.requiringNew().run(() -> {
            assertEquals(1, within(around), "hall is within the big box");
            assertEquals(0, within(elsewhere));
            assertEquals(0, within(touching), "the hall is not within a box that only overlaps it");
            assertEquals(1, intersecting(touching));
            assertEquals(0, intersecting(elsewhere));
        });
    }

    private int within(Geometry filter) {
        return em.createQuery(
                        "select r from AssetRevisionEntity r where st_within(r.geom, :f) = true",
                        AssetRevisionEntity.class)
                .setParameter("f", filter)
                .getResultList()
                .size();
    }

    private int intersecting(Geometry filter) {
        return em.createQuery(
                        "select r from AssetRevisionEntity r where st_intersects(r.geom, :f) = true",
                        AssetRevisionEntity.class)
                .setParameter("f", filter)
                .getResultList()
                .size();
    }

    private static Geometry box(double x0, double y0, double x1, double y1) {
        Geometry g = Footprints.FACTORY.createPolygon(new Coordinate[] {
            new Coordinate(x0, y0),
            new Coordinate(x1, y0),
            new Coordinate(x1, y1),
            new Coordinate(x0, y1),
            new Coordinate(x0, y0)
        });
        g.setSRID(Footprints.SRID);
        return g;
    }

    @Test
    void rowVersionCountsUpdatesAndDecimalsKeepTheirScale() {
        var r = revision(asset, Footprints.FACTORY.createPolygon(HALL));
        r.headingDeg = new BigDecimal("210.299");
        r.lengthM = new BigDecimal("40.125");
        QuarkusTransaction.requiringNew().run(() -> em.persist(r));

        QuarkusTransaction.requiringNew().run(() -> {
            var loaded = em.find(AssetRevisionEntity.class, new AssetRevisionId(asset, version));
            assertEquals(0, loaded.rowVersion);
            assertEquals(0, new BigDecimal("210.299").compareTo(loaded.headingDeg));
            loaded.name = "Hall renamed";
        });

        var again = QuarkusTransaction.requiringNew()
                .call(() -> em.find(AssetRevisionEntity.class, new AssetRevisionId(asset, version)));
        assertEquals(1, again.rowVersion);
        assertEquals("Hall renamed", again.name);
        assertEquals(0, new BigDecimal("40.125").compareTo(again.lengthM));
    }

    @Test
    void theAssetIsFoundThroughTheRevision() {
        QuarkusTransaction.requiringNew()
                .run(() -> em.persist(revision(asset, Footprints.FACTORY.createPolygon(HALL))));

        List<AssetRevisionEntity> rows = QuarkusTransaction.requiringNew().call(() -> {
            var list = em.createQuery(
                            "select r from AssetRevisionEntity r join fetch r.asset where r.id.layoutVersionId = :v",
                            AssetRevisionEntity.class)
                    .setParameter("v", version)
                    .getResultList();
            assertEquals("WH1", list.get(0).asset.code);
            return list;
        });
        assertEquals(1, rows.size());
    }

    @Test
    void batchSizeIs100() {
        assertEquals(
                100,
                ConfigProvider.getConfig().getValue("quarkus.hibernate-orm.jdbc.statement-batch-size", Integer.class));
    }
}
