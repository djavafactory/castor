package com.djavafactory.castor.core.layout.infrastructure;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.geometry.application.GeoJsonMapper;
import com.djavafactory.castor.core.geometry.domain.LonLat;
import com.djavafactory.castor.core.geometry.domain.QuayFrame;
import com.djavafactory.castor.core.layout.domain.AssetType;
import com.djavafactory.castor.core.layout.domain.AssetTypeRepository;
import com.djavafactory.castor.core.layout.domain.Footprints;
import com.djavafactory.castor.core.layout.domain.LayoutFeatureValidator;
import com.djavafactory.castor.core.layout.domain.LayoutRepository;
import com.djavafactory.castor.core.layout.domain.LayoutStatus;
import com.djavafactory.castor.core.port.domain.PortRepository;
import com.djavafactory.castor.core.support.LayoutTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Westports demo seed (CORE-04 part 6) loaded by LayoutSeedJob into a real PostGIS. */
@QuarkusTest
class LayoutSeedIT {

    @Inject
    LayoutSeedJob job;

    @Inject
    DataSource dataSource;

    @Inject
    LayoutRepository layouts;

    @Inject
    AssetTypeRepository types;

    @Inject
    PortRepository ports;

    @Inject
    ObjectMapper json;

    @Inject
    GeoJsonMapper geo;

    @BeforeEach
    @AfterEach
    void clear() {
        LayoutTestSupport.clear(dataSource);
    }

    private long count(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return LayoutTestSupport.scalar(c, sql, Long.class);
        }
    }

    @Test
    void firstSeedPublishesVersionOneCreatedBySeed() throws SQLException {
        assertEquals(301, job.seed());

        var version = layouts.findPublished("westports").orElseThrow();
        assertEquals(1, version.versionNo());
        assertEquals(LayoutStatus.PUBLISHED, version.status());
        assertEquals("seed", version.createdBy());
        assertEquals("seed", version.publishedBy());
        assertEquals("Westports demo layout", version.note());
        assertNotNull(version.publishedAt());

        var port = ports.findByCode("westports").orElseThrow();
        assertEquals(version.id(), port.publishedLayoutVersionId());
        assertEquals(13, ports.countPublishedBerths(port));
        assertEquals(301, count("select count(*) from layout.asset"));
        assertEquals(301, count("select count(*) from layout.asset_revision"));
        assertEquals(
                1,
                count("select count(*) from ref.import_batch where kind = 'LAYOUT_SEED' "
                        + "and source_code = 'SYNTHETIC' and status = 'SUCCEEDED' and row_count = 301"));
    }

    @Test
    void seedingTwiceChangesNothing() throws SQLException {
        job.seed();
        String before = fingerprint();

        assertEquals(0, job.seed());

        assertEquals(before, fingerprint());
        assertEquals(1, count("select count(*) from layout.layout_version"));
        assertEquals(1, count("select count(*) from ref.import_batch where kind = 'LAYOUT_SEED'"));
    }

    @Test
    void anAssetIdentityIsFoundByPortAndCodeWhenTheVersionIsMissing() throws SQLException {
        job.seed();
        String ids = fingerprint();
        // lose the version but keep the permanent identities, as after a discarded import
        LayoutTestSupport.clear(dataSource);
        try (Connection c = dataSource.getConnection()) {
            assertEquals(0, count("select count(*) from layout.asset"));
        }
        job.seed();
        assertEquals(ids.split(",").length, fingerprint().split(",").length);
    }

    private String fingerprint() throws SQLException {
        try (Connection c = dataSource.getConnection();
                ResultSet rs = c.createStatement()
                        .executeQuery("select string_agg(code || ':' || id, ',' order by code) from layout.asset")) {
            rs.next();
            return rs.getString(1);
        }
    }

    @Test
    void everySeededAssetPassesValidationWithZeroErrors() throws Exception {
        Map<String, AssetType> typeMap = types.findAll().stream().collect(Collectors.toMap(AssetType::code, t -> t));
        var port = ports.findByCode("westports").orElseThrow();
        var frame = new QuayFrame(port.origin().lon(), port.origin().lat(), port.frameRotDeg());
        var extent = geo.read(port.extent());
        byte[] file;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("seed/westports/layout-v1.geojson")) {
            file = in.readAllBytes();
        }
        var parsed = new SeedLayoutParser(json, geo).parse(file);

        var violations = new LayoutFeatureValidator(typeMap, frame, extent).validate(parsed.features());

        assertEquals(List.of(), violations);
        assertEquals(301, parsed.features().size());
    }

    @Test
    void everySeededGeometryLiesInsideThePortExtent() throws SQLException {
        job.seed();
        assertEquals(
                0,
                count("select count(*) from layout.asset_revision r, ref.port p "
                        + "where p.code = 'westports' and not ST_CoveredBy(r.geom, p.extent)"));
    }

    @Test
    void contentMatchesTheExpectedWestportsLayout() throws SQLException {
        job.seed();
        Map<String, Long> byType = new HashMap<>();
        try (Connection c = dataSource.getConnection();
                ResultSet rs =
                        c.createStatement().executeQuery("select type_code, count(*) from layout.asset group by 1")) {
            while (rs.next()) {
                byType.put(rs.getString(1), rs.getLong(2));
            }
        }
        assertEquals(13, byType.get("BERTH"));
        assertEquals(9, byType.get("QUAY_CRANE_RAIL"));
        assertEquals(24, byType.get("STS_CRANE"));
        assertEquals(48, byType.get("RTG"));
        assertEquals(48, byType.get("CONTAINER_YARD"));
        assertEquals(23, byType.get("STORAGE_PAD"));
        assertEquals(3, byType.get("SILO"));
        assertEquals(8, byType.get("LIQUID_TANK"));
        assertEquals(2, byType.get("EXCLUSION_ZONE"));
        assertEquals(36, byType.get("BOLLARD"));
        assertEquals(13, byType.get("FENDER"));
        assertEquals(82, count("select count(*) from layout.asset_link where kind = 'SERVES'"));
        assertEquals(5, count("select count(*) from layout.asset_link where kind = 'FEEDS'"));
        assertEquals(1, count("select count(*) from layout.asset where code = 'CB8'"));
        assertEquals(1, count("select count(*) from layout.asset where code = 'STS-CB8-C'"));
        assertEquals(1, count("select count(*) from layout.asset where code = 'PAD-R2'"));
        // berth B1: 400 m long, depth 16.5 m, conventional cargo families
        assertEquals(
                1,
                count("select count(*) from layout.berth_detail d join layout.asset a on a.id = d.asset_id "
                        + "where a.code = 'B1' and d.depth_m = 16.5 and d.max_loa_m = 400"));
        // every row says SYNTHETIC, APPROXIMATE, captured on 2026-10-21
        assertEquals(
                301,
                count("select count(*) from layout.asset_revision where source_code = 'SYNTHETIC' "
                        + "and accuracy_code = 'APPROXIMATE' and captured_on = date '2026-10-21' "
                        + "and provenance_note = 'Synthetic placeholder, not a survey'"));
    }

    @Test
    void storedFootprintsEqualTheOnesRebuiltFromTheirFieldsWithinOneCentimetre() {
        job.seed();
        var version = layouts.findPublished("westports").orElseThrow();
        var port = ports.findByCode("westports").orElseThrow();
        var frame = new QuayFrame(port.origin().lon(), port.origin().lat(), port.frameRotDeg());
        Map<String, AssetType> typeMap = types.findAll().stream().collect(Collectors.toMap(AssetType::code, t -> t));
        int checked = 0;
        for (var a : layouts.load(version).assets()) {
            var type = typeMap.get(a.typeCode());
            if (!List.of("POINT", "RECT", "CIRCLE").contains(type.geometryKind())) {
                continue;
            }
            var rebuilt = Footprints.derive(type, a, frame).getCoordinates();
            var stored = a.geom().getCoordinates();
            assertEquals(rebuilt.length, stored.length, a.code());
            for (int i = 0; i < stored.length; i++) {
                var p = frame.toLocal(new LonLat(rebuilt[i].x, rebuilt[i].y));
                var q = frame.toLocal(new LonLat(stored[i].x, stored[i].y));
                assertTrue(Math.hypot(p.x() - q.x(), p.y() - q.y()) < 0.01, a.code() + " vertex " + i);
            }
            checked++;
        }
        assertTrue(checked > 200, "checked " + checked);
    }

    @Test
    void theFourControlPointsConvertWithinTenCentimetres() throws SQLException {
        var port = ports.findByCode("westports").orElseThrow();
        var frame = new QuayFrame(port.origin().lon(), port.origin().lat(), port.frameRotDeg());
        int n = 0;
        try (Connection c = dataSource.getConnection();
                ResultSet rs = c.createStatement()
                        .executeQuery("select code, ST_X(geom), ST_Y(geom), expected_local_x, expected_local_y "
                                + "from ref.control_point")) {
            while (rs.next()) {
                var local = frame.toLocal(rs.getDouble(2), rs.getDouble(3));
                double off = Math.hypot(local.x() - rs.getDouble(4), local.y() - rs.getDouble(5));
                assertTrue(off < 0.1, rs.getString(1) + " is off by " + off + " m");
                n++;
            }
        }
        assertEquals(4, n);
    }

    @Test
    void seedAssetIdsAreStableUuidsAndLinksPointAtRealAssets() {
        job.seed();
        var version = layouts.findPublished("westports").orElseThrow();
        var assets = layouts.load(version).assets();
        var ids = assets.stream().map(a -> a.id()).collect(Collectors.toSet());
        assertEquals(301, ids.size());
        for (var a : assets) {
            for (var l : a.links()) {
                assertTrue(ids.contains(l.toAssetId()), a.code() + " links to an unknown asset");
                assertTrue(l.toAssetId() instanceof UUID);
            }
        }
    }
}
