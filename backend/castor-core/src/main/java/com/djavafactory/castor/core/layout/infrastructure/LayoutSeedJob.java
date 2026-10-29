package com.djavafactory.castor.core.layout.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import jakarta.persistence.EntityManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;
import org.locationtech.jts.geom.Point;

import com.djavafactory.castor.core.geometry.application.GeoJsonMapper;
import com.djavafactory.castor.core.geometry.domain.QuayFrame;
import com.djavafactory.castor.core.layout.domain.AssetType;
import com.djavafactory.castor.core.layout.domain.AssetTypeRepository;
import com.djavafactory.castor.core.layout.domain.FeatureDraft;
import com.djavafactory.castor.core.layout.domain.Footprints;
import com.djavafactory.castor.core.layout.domain.LayoutFeatureValidator;
import com.djavafactory.castor.core.layout.domain.LayoutStatus;
import com.djavafactory.castor.core.port.domain.Port;
import com.djavafactory.castor.core.port.domain.PortRepository;
import com.djavafactory.castor.core.shared.application.DataSeedStep;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;
import com.djavafactory.castor.core.shared.infrastructure.CastorConfig;

/**
 * Loads the Westports demo layout as published version 1. It runs when the port has no layout version (first start in
 * local) and after a reset in dev, only in local, dev and test; a protected environment never gets demo data.
 *
 * <p>Every feature is validated with the domain rules before anything is written. Identities, revisions, details and
 * links are inserted in one transaction together with one ref.import_batch (kind LAYOUT_SEED, source SYNTHETIC), the
 * version is created as a draft and then published with created_by and published_by "seed". Running it again changes
 * nothing: a port that already has a version is left alone and assets are matched by (port, code).
 */
@ApplicationScoped
public class LayoutSeedJob implements DataSeedStep {

    private static final Logger LOG = Logger.getLogger(LayoutSeedJob.class);
    private static final String SEED_USER = "seed";

    private final CastorConfig castor;
    private final PortRepository ports;
    private final AssetTypeRepository types;
    private final EntityManager em;
    private final DataSource dataSource;
    private final SeedLayoutParser parser;
    private final GeoJsonMapper geo;

    public LayoutSeedJob(
            CastorConfig castor,
            PortRepository ports,
            AssetTypeRepository types,
            EntityManager em,
            DataSource dataSource,
            ObjectMapper json,
            GeoJsonMapper geo) {
        this.castor = castor;
        this.ports = ports;
        this.types = types;
        this.em = em;
        this.dataSource = dataSource;
        this.geo = geo;
        this.parser = new SeedLayoutParser(json, geo);
    }

    /** After the reset hook (PLATFORM_AFTER) and the OSM import (+50), before the account bootstrap (+100). */
    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_AFTER + 60) StartupEvent event) {
        if (!castor.layoutSeedActive()) {
            if (castor.layout().seedOnStart()) {
                LOG.infof("Demo layout not loaded: CASTOR_ENV=%s is not local, dev or test", castor.env());
            }
            return;
        }
        try {
            seed();
        } catch (RuntimeException e) {
            LOG.errorf(e, "Westports layout seed failed, the port has no layout until it is fixed");
        }
    }

    /** The reset hook calls this after the schemas are recreated; returns the number of assets created. */
    @Override
    public int seed() {
        if (!CastorConfig.KNOWN_ENVIRONMENTS.contains(castor.env())) {
            LOG.warnf("Environment '%s' is protected: the demo layout is not loaded", castor.env());
            return 0;
        }
        String resource = "seed/" + castor.portCode() + "/layout-v1.geojson";
        byte[] file;
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                LOG.warnf("Layout file %s not found, no demo layout is loaded", resource);
                return 0;
            }
            file = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + resource, e);
        }
        return QuarkusTransaction.requiringNew().call(() -> load(file));
    }

    private int load(byte[] file) {
        Port port = ports.findByCode(castor.portCode())
                .orElseThrow(() -> new ResourceNotFoundException("No port with code '" + castor.portCode() + "'"));
        try (Connection c = dataSource.getConnection()) {
            if (count(c, "select count(*) from layout.layout_version where port_id = ?", port.id()) > 0) {
                return 0;
            }
            var parsed = parser.parse(file);
            if (!parsed.header().port().equals(port.code()) || parsed.header().versionNo() != 1) {
                throw new IllegalStateException(
                        "The layout file is for " + parsed.header().port() + " version "
                                + parsed.header().versionNo() + ", expected " + port.code() + " version 1");
            }
            Map<String, AssetType> typeMap =
                    types.findAll().stream().collect(Collectors.toMap(AssetType::code, t -> t));
            var frame = new QuayFrame(port.origin().lon(), port.origin().lat(), port.frameRotDeg());
            var extent = geo.read(port.extent());
            new LayoutFeatureValidator(typeMap, frame, extent).requireValid(parsed.features());

            long batch = startBatch(c, port.id());
            UUID versionId = createDraft(port.id(), parsed.header().note());
            Map<String, UUID> assetIds = writeAssets(port.id(), versionId, parsed.features(), typeMap, frame);
            em.flush();
            for (FeatureDraft d : parsed.features()) {
                UUID id = assetIds.get(d.code());
                DetailStore.write(c, versionId, id, d.asset().details());
                for (FeatureDraft.DraftLink l : d.links()) {
                    DetailStore.writeLink(c, versionId, id, assetIds.get(l.toCode()), l.kind());
                }
            }
            publish(versionId);
            em.flush();
            update(
                    c,
                    "update ref.port set current_published_layout_id = ?, updated_at = now() where id = ?",
                    versionId,
                    port.id());
            finishBatch(c, batch, parsed.features().size(), sha256(file));
            LOG.infof("Loaded the %s demo layout: %d assets, published version 1", port.code(), assetIds.size());
            return assetIds.size();
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Layout seed failed: " + e.getMessage(), e);
        }
    }

    private UUID createDraft(long portId, String note) {
        var v = new LayoutVersionEntity();
        v.id = UUID.randomUUID();
        v.portId = portId;
        v.versionNo = 1;
        v.status = LayoutStatus.DRAFT;
        v.note = note == null ? "Westports demo layout" : note;
        v.createdBy = SEED_USER;
        em.persist(v);
        em.flush();
        return v.id;
    }

    private void publish(UUID versionId) {
        var v = em.find(LayoutVersionEntity.class, versionId);
        v.status = LayoutStatus.PUBLISHED;
        v.publishedBy = SEED_USER;
        v.publishedAt = Instant.now();
    }

    /** Finds or creates each identity by (port, code), then adds the revision with a footprint rebuilt in the domain. */
    private Map<String, UUID> writeAssets(
            long portId, UUID versionId, List<FeatureDraft> drafts, Map<String, AssetType> typeMap, QuayFrame frame) {
        Map<String, UUID> ids = new HashMap<>();
        em.createQuery("select a from AssetEntity a where a.portId = :p", AssetEntity.class)
                .setParameter("p", portId)
                .getResultList()
                .forEach(a -> ids.put(a.code, a.id));
        for (FeatureDraft d : drafts) {
            if (!ids.containsKey(d.code())) {
                var a = new AssetEntity();
                a.id = UUID.randomUUID();
                a.portId = portId;
                a.code = d.code();
                a.typeCode = d.asset().typeCode();
                a.createdBy = SEED_USER;
                em.persist(a);
                ids.put(a.code, a.id);
            }
        }
        em.flush();
        for (FeatureDraft d : drafts) {
            var a = d.asset();
            var r = new AssetRevisionEntity();
            r.id = new AssetRevisionId(ids.get(d.code()), versionId);
            r.name = a.name();
            r.geom = Footprints.derive(typeMap.get(a.typeCode()), a, frame);
            r.anchor = point(a.anchor().lon(), a.anchor().lat());
            r.headingDeg = a.headingDeg();
            r.lengthM = a.lengthM();
            r.widthM = a.widthM();
            r.heightM = a.heightM();
            r.radiusM = a.radiusM();
            r.lineWidthM = a.lineWidthM();
            r.elevationM = a.elevationM();
            r.clearanceM = a.clearanceM();
            r.modelRev = typeMap.get(a.typeCode()).modelRev();
            r.storageZoneCode = a.storageZoneCode();
            r.accuracyCode = a.provenance().accuracy();
            r.sourceCode = a.provenance().source();
            r.capturedOn = a.provenance().capturedOn();
            r.provenanceNote = a.provenance().note();
            r.createdBy = SEED_USER;
            r.updatedBy = SEED_USER;
            em.persist(r);
        }
        return ids;
    }

    private static Point point(double lon, double lat) {
        Point p = Footprints.FACTORY.createPoint(new org.locationtech.jts.geom.Coordinate(lon, lat));
        p.setSRID(Footprints.SRID);
        return p;
    }

    private long startBatch(Connection c, long portId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into ref.import_batch (port_id, source_code, kind) values (?, 'SYNTHETIC', 'LAYOUT_SEED') "
                        + "returning id")) {
            ps.setLong(1, portId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void finishBatch(Connection c, long batch, int rows, String checksum) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("update ref.import_batch set status = 'SUCCEEDED', "
                + "finished_at = now(), row_count = ?, checksum = ? where id = ?")) {
            ps.setInt(1, rows);
            ps.setString(2, checksum);
            ps.setLong(3, batch);
            ps.executeUpdate();
        }
    }

    private static long count(Connection c, String sql, Object arg) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
