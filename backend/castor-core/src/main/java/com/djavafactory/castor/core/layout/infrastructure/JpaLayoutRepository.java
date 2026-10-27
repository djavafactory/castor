package com.djavafactory.castor.core.layout.infrastructure;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import com.djavafactory.castor.core.geometry.domain.LonLat;
import com.djavafactory.castor.core.layout.domain.AssetDetails;
import com.djavafactory.castor.core.layout.domain.AssetMeasures;
import com.djavafactory.castor.core.layout.domain.LayoutAsset;
import com.djavafactory.castor.core.layout.domain.LayoutRepository;
import com.djavafactory.castor.core.layout.domain.LayoutStatus;
import com.djavafactory.castor.core.layout.domain.LayoutVersion;
import com.djavafactory.castor.core.layout.domain.LayoutView;
import com.djavafactory.castor.core.layout.domain.StoragePadCapacity;

/**
 * Reads layouts: versions with SQL (they carry the port code), revisions through the {@link AssetRevisionEntity}
 * mapping so geometry arrives as JTS exactly as stored, details and measures with one SQL statement per table.
 */
@ApplicationScoped
public class JpaLayoutRepository implements LayoutRepository {

    private static final String VERSION_SQL = "select v.id, p.code, v.version_no, v.status, v.note, v.created_by, "
            + "v.created_at, v.published_by, v.published_at, v.based_on_version_id, v.row_version "
            + "from layout.layout_version v join ref.port p on p.id = v.port_id ";

    private final EntityManager em;
    private final DataSource dataSource;

    public JpaLayoutRepository(EntityManager em, DataSource dataSource) {
        this.em = em;
        this.dataSource = dataSource;
    }

    @Override
    @Transactional
    public List<LayoutVersion> findLatestVersions() {
        return versions("where v.version_no = (select max(x.version_no) from layout.layout_version x "
                + "where x.port_id = v.port_id) order by p.code");
    }

    @Override
    @Transactional
    public Optional<LayoutVersion> findPublished(String portCode) {
        return versions("where p.code = ? and v.status = 'PUBLISHED'", portCode).stream()
                .findFirst();
    }

    @Override
    @Transactional
    public Optional<LayoutVersion> findDraft(String portCode) {
        return versions("where p.code = ? and v.status = 'DRAFT'", portCode).stream()
                .findFirst();
    }

    @Override
    @Transactional
    public Optional<LayoutVersion> findVersion(UUID layoutId) {
        return versions("where v.id = ?", layoutId).stream().findFirst();
    }

    @Override
    @Transactional
    public LayoutView load(LayoutVersion version) {
        return assemble(version, null);
    }

    @Override
    @Transactional
    public Optional<LayoutView> loadAsset(LayoutVersion version, UUID assetId) {
        LayoutView view = assemble(version, assetId);
        return view.assets().isEmpty() ? Optional.empty() : Optional.of(view);
    }

    private LayoutView assemble(LayoutVersion version, UUID only) {
        var query = em.createQuery(
                "select r from AssetRevisionEntity r join fetch r.asset a where r.id.layoutVersionId = :v "
                        + (only == null ? "" : "and r.id.assetId = :a ")
                        + "order by a.code",
                AssetRevisionEntity.class);
        query.setParameter("v", version.id());
        if (only != null) {
            query.setParameter("a", only);
        }
        List<AssetRevisionEntity> rows = query.getResultList();
        try (Connection c = dataSource.getConnection()) {
            Map<UUID, AssetDetails> details = DetailStore.read(c, version.id());
            var links = DetailStore.readLinks(c, version.id());
            Map<UUID, AssetMeasures> measures = readMeasures(c, version.id());
            Map<String, StoragePadCapacity.Cargo> cargo = readCargo(c);
            List<LayoutAsset> assets = new ArrayList<>(rows.size());
            for (AssetRevisionEntity r : rows) {
                UUID id = r.id.assetId;
                var d = details.getOrDefault(id, AssetDetails.NONE);
                var assetLinks = links.getOrDefault(id, List.of()).stream()
                        .map(e -> new LayoutAsset.Link(e.getKey(), e.getValue()))
                        .toList();
                assets.add(toAsset(r, withPadCapacity(d, cargo), assetLinks));
            }
            Map<UUID, AssetMeasures> wanted = new HashMap<>(measures);
            if (only != null) {
                wanted.keySet().removeIf(k -> !k.equals(only));
            }
            return new LayoutView(version, assets, wanted, etagSource(c, version));
        } catch (SQLException e) {
            throw new IllegalStateException("Reading layout " + version.id() + " failed", e);
        }
    }

    private static LayoutAsset toAsset(AssetRevisionEntity r, AssetDetails details, List<LayoutAsset.Link> links) {
        return new LayoutAsset(
                r.id.assetId,
                r.asset.code,
                r.asset.typeCode,
                r.name,
                r.geom,
                new LonLat(r.anchor.getX(), r.anchor.getY()),
                r.headingDeg,
                r.lengthM,
                r.widthM,
                r.heightM,
                r.radiusM,
                r.lineWidthM,
                r.elevationM,
                r.clearanceM,
                r.modelRev,
                r.storageZoneCode,
                r.operatorName,
                r.description,
                new LayoutAsset.Provenance(r.sourceCode, r.accuracyCode, r.capturedOn, r.provenanceNote),
                r.overrideReason == null
                        ? null
                        : new LayoutAsset.Override(r.overrideReason, r.overrideBy, r.overrideAt),
                details,
                links,
                r.rowVersion);
    }

    /** Fills the read-only computed capacity of a pad from its cargo. */
    private static AssetDetails withPadCapacity(AssetDetails d, Map<String, StoragePadCapacity.Cargo> cargo) {
        if (d.storagePad() == null || d.storage() == null || d.storage().cargoTypeCode() == null) {
            return d;
        }
        var c = cargo.get(d.storage().cargoTypeCode());
        if (c == null) {
            return d;
        }
        var p = d.storagePad();
        double tonnes = Math.round(StoragePadCapacity.padTonnes(p, c));
        var pad = new AssetDetails.StoragePad(p.bays(), p.rows(), p.bayPitchM(), p.rowPitchM(), p.maxHeightM(), tonnes);
        return new AssetDetails(
                d.berth(),
                d.crane(),
                d.storage(),
                pad,
                d.containerYard(),
                d.warehouse(),
                d.flow(),
                d.surface(),
                d.zone(),
                d.mobile(),
                d.equipment());
    }

    private static Map<UUID, AssetMeasures> readMeasures(Connection c, UUID version) throws SQLException {
        Map<UUID, AssetMeasures> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("select asset_id, area_m2, perimeter_m, length_m "
                + "from layout.asset_revision_measure where layout_version_id = ?")) {
            ps.setObject(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(
                            rs.getObject(1, UUID.class),
                            new AssetMeasures(
                                    nullableDouble(rs, 2), nullableDouble(rs, 3), nullableDouble(rs, 4), null, null));
                }
            }
        }
        return out;
    }

    private static Map<String, StoragePadCapacity.Cargo> readCargo(Connection c) throws SQLException {
        Map<String, StoragePadCapacity.Cargo> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                        "select code, storage_form, density_t_m3, max_stack_m, repose_deg from ref.cargo_type");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(
                        rs.getString(1),
                        new StoragePadCapacity.Cargo(
                                rs.getString(2), rs.getDouble(3), nullableDouble(rs, 4), nullableDouble(rs, 5)));
            }
        }
        return out;
    }

    /** Changes whenever the version row or any of its revisions is added, removed or updated. */
    private static String etagSource(Connection c, LayoutVersion version) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select count(*), coalesce(max(row_version), 0) "
                + "from layout.asset_revision where layout_version_id = ?")) {
            ps.setObject(1, version.id());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return version.id() + "-" + version.rowVersion() + "-" + rs.getLong(2) + "-" + rs.getLong(1);
            }
        }
    }

    private List<LayoutVersion> versions(String where, Object... args) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(VERSION_SQL + where)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<LayoutVersion> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new LayoutVersion(
                            rs.getObject(1, UUID.class),
                            rs.getString(2),
                            rs.getInt(3),
                            LayoutStatus.valueOf(rs.getString(4)),
                            rs.getString(5),
                            rs.getString(6),
                            instant(rs, 7),
                            rs.getString(8),
                            instant(rs, 9),
                            rs.getObject(10, UUID.class),
                            rs.getLong(11)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Reading layout versions failed", e);
        }
    }

    /** The driver cannot convert numeric to Double through getObject, and SQL null must stay null. */
    private static Double nullableDouble(ResultSet rs, int column) throws SQLException {
        double v = rs.getDouble(column);
        return rs.wasNull() ? null : v;
    }

    private static java.time.Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
