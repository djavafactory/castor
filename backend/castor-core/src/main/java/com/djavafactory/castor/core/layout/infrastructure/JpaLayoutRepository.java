package com.djavafactory.castor.core.layout.infrastructure;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Point;

import com.djavafactory.castor.core.geometry.domain.LonLat;
import com.djavafactory.castor.core.layout.domain.AssetDetails;
import com.djavafactory.castor.core.layout.domain.AssetHistoryEntry;
import com.djavafactory.castor.core.layout.domain.AssetMeasures;
import com.djavafactory.castor.core.layout.domain.Footprints;
import com.djavafactory.castor.core.layout.domain.LayoutAsset;
import com.djavafactory.castor.core.layout.domain.LayoutRepository;
import com.djavafactory.castor.core.layout.domain.LayoutStatus;
import com.djavafactory.castor.core.layout.domain.LayoutVersion;
import com.djavafactory.castor.core.layout.domain.LayoutView;
import com.djavafactory.castor.core.layout.domain.StoragePadCapacity;
import com.djavafactory.castor.core.layout.domain.VersionSummary;
import com.djavafactory.castor.core.shared.domain.ConflictException;
import com.djavafactory.castor.core.shared.domain.DraftAlreadyExistsException;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;

/**
 * Reads layouts: versions with SQL (they carry the port code), revisions through the {@link AssetRevisionEntity}
 * mapping so geometry arrives as JTS exactly as stored, details and measures with one SQL statement per table.
 */
@ApplicationScoped
public class JpaLayoutRepository implements LayoutRepository {

    private static final String VERSION_SQL = "select v.id, p.code, v.version_no, v.status, v.note, v.created_by, "
            + "v.created_at, v.published_by, v.published_at, v.based_on_version_id, b.version_no, "
            + "coalesce(l.updated_by, v.created_by), coalesce(l.updated_at, v.created_at), v.row_version "
            + "from layout.layout_version v join ref.port p on p.id = v.port_id "
            + "left join layout.layout_version b on b.id = v.based_on_version_id "
            + "left join lateral (select r.updated_by, r.updated_at from layout.asset_revision r "
            + "where r.layout_version_id = v.id and r.updated_at > v.created_at order by r.updated_at desc limit 1) l on true ";

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

    @Override
    @Transactional
    public LayoutVersion createDraft(String portCode, LayoutVersion basedOn, String createdBy) {
        // the port row serialises concurrent creators, so the unique draft index is never the first line of defence
        Object portId = em.createNativeQuery("select id from ref.port where code = :code for update")
                .setParameter("code", portCode)
                .getSingleResult();
        var existing = findDraft(portCode);
        if (existing.isPresent()) {
            throw new DraftAlreadyExistsException(existing.get().id());
        }
        UUID id = UUID.randomUUID();
        em.createNativeQuery("insert into layout.layout_version (id, port_id, version_no, status, "
                        + "based_on_version_id, created_by) values (:id, :port, "
                        + "(select coalesce(max(version_no), 0) + 1 from layout.layout_version where port_id = :port), "
                        + "'DRAFT', :basedOn, :by)")
                .setParameter("id", id)
                .setParameter("port", ((Number) portId).longValue())
                .setParameter("basedOn", basedOn == null ? null : basedOn.id())
                .setParameter("by", createdBy)
                .executeUpdate();
        if (basedOn != null) {
            em.createNativeQuery("select layout.copy_revisions(:from, :to)")
                    .setParameter("from", basedOn.id())
                    .setParameter("to", id)
                    .getSingleResult();
        }
        return findVersion(id).orElseThrow();
    }

    @Override
    @Transactional
    public LayoutVersion discard(LayoutVersion draft) {
        em.createNativeQuery("update layout.layout_version set status = 'DISCARDED', row_version = row_version + 1 "
                        + "where id = :id and status = 'DRAFT'")
                .setParameter("id", draft.id())
                .executeUpdate();
        return findVersion(draft.id()).orElseThrow();
    }

    @Override
    @Transactional
    public Optional<AssetIdentity> findIdentity(String portCode, UUID assetId) {
        var a = em.find(AssetEntity.class, assetId);
        if (a == null) {
            return Optional.empty();
        }
        Object code = em.createNativeQuery("select code from ref.port where id = :id")
                .setParameter("id", a.portId)
                .getSingleResult();
        return portCode.equals(code) ? Optional.of(new AssetIdentity(a.id, a.code, a.typeCode)) : Optional.empty();
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public List<String> findCodes(String portCode) {
        return em.createNativeQuery("select a.code from layout.asset a join ref.port p on p.id = a.port_id "
                        + "where p.code = :code")
                .setParameter("code", portCode)
                .getResultList();
    }

    @Override
    @Transactional
    public Optional<Long> findRowVersion(UUID versionId, UUID assetId) {
        var r = em.find(AssetRevisionEntity.class, new AssetRevisionId(assetId, versionId));
        return r == null ? Optional.empty() : Optional.of(r.rowVersion);
    }

    @Override
    @Transactional
    public long insert(LayoutVersion draft, LayoutAsset a, String actor) {
        if (em.find(AssetEntity.class, a.id()) == null) {
            Number portId = (Number) em.createNativeQuery("select id from ref.port where code = :code")
                    .setParameter("code", draft.portCode())
                    .getSingleResult();
            var identity = new AssetEntity();
            identity.id = a.id();
            identity.portId = portId.longValue();
            identity.code = a.code();
            identity.typeCode = a.typeCode();
            identity.createdBy = actor;
            em.persist(identity);
            em.flush();
        }
        var r = new AssetRevisionEntity();
        r.id = new AssetRevisionId(a.id(), draft.id());
        r.createdBy = actor;
        r.updatedBy = actor;
        copy(a, r);
        em.persist(r);
        em.flush();
        writeDetails(draft.id(), a, false);
        long version = r.rowVersion;
        em.clear();
        return version;
    }

    @Override
    @Transactional
    public long update(LayoutVersion draft, LayoutAsset a, long expectedRowVersion, String actor) {
        var r = locked(draft.id(), a.id(), expectedRowVersion);
        copy(a, r);
        r.updatedBy = actor;
        r.updatedAt = Instant.now();
        em.flush();
        writeDetails(draft.id(), a, true);
        long version = r.rowVersion;
        em.clear();
        return version;
    }

    @Override
    @Transactional
    public long override(
            LayoutVersion draft, UUID assetId, long expectedRowVersion, LayoutAsset.Override o, String actor) {
        var r = locked(draft.id(), assetId, expectedRowVersion);
        r.overrideReason = o.reason();
        r.overrideBy = o.by();
        r.overrideAt = o.at();
        r.updatedBy = actor;
        r.updatedAt = Instant.now();
        em.flush();
        long version = r.rowVersion;
        em.clear();
        return version;
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public List<String> delete(LayoutVersion draft, UUID assetId, long expectedRowVersion) {
        var r = locked(draft.id(), assetId, expectedRowVersion);
        List<String> dependents = em.createNativeQuery("select distinct a.code from layout.asset_link l "
                        + "join layout.asset a on a.id = l.from_asset_id "
                        + "where l.layout_version_id = :v and l.to_asset_id = :id order by 1")
                .setParameter("v", draft.id())
                .setParameter("id", assetId)
                .getResultList();
        em.remove(r);
        em.flush();
        return dependents;
    }

    private AssetRevisionEntity locked(UUID versionId, UUID assetId, long expectedRowVersion) {
        var r = em.find(AssetRevisionEntity.class, new AssetRevisionId(assetId, versionId));
        if (r == null) {
            throw new ResourceNotFoundException("Layout " + versionId + " has no asset " + assetId);
        }
        if (r.rowVersion != expectedRowVersion) {
            throw new ConflictException("The asset was changed by someone else, reload and try again");
        }
        return r;
    }

    private static void copy(LayoutAsset a, AssetRevisionEntity r) {
        r.name = a.name();
        r.geom = a.geom();
        r.anchor = point(a.anchor());
        r.headingDeg = a.headingDeg();
        r.lengthM = a.lengthM();
        r.widthM = a.widthM();
        r.heightM = a.heightM();
        r.radiusM = a.radiusM();
        r.lineWidthM = a.lineWidthM();
        r.elevationM = a.elevationM() == null ? BigDecimal.ZERO : a.elevationM();
        r.clearanceM = a.clearanceM() == null ? BigDecimal.ZERO : a.clearanceM();
        r.modelRev = a.modelRev();
        r.storageZoneCode = a.storageZoneCode();
        r.operatorName = a.operatorName();
        r.description = a.description();
        r.accuracyCode = a.provenance().accuracy();
        r.sourceCode = a.provenance().source();
        r.capturedOn = a.provenance().capturedOn();
        r.provenanceNote = a.provenance().note();
    }

    private static Point point(LonLat p) {
        Point pt = Footprints.FACTORY.createPoint(new Coordinate(p.lon(), p.lat()));
        pt.setSRID(Footprints.SRID);
        return pt;
    }

    /** Writes details and links once the revision is flushed; an update clears the old rows first. */
    private void writeDetails(UUID versionId, LayoutAsset a, boolean clear) {
        try (Connection c = dataSource.getConnection()) {
            if (clear) {
                DetailStore.clear(c, versionId, a.id());
            }
            DetailStore.write(c, versionId, a.id(), a.details());
            for (LayoutAsset.Link l : a.links()) {
                DetailStore.writeLink(c, versionId, a.id(), l.toAssetId(), l.kind());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Writing the details of " + a.id() + " failed", e);
        }
    }

    @Override
    @Transactional
    public void markRestored(UUID draftId, UUID sourceVersionId) {
        em.createNativeQuery("update layout.layout_version set restored_from_version_id = :src, "
                        + "row_version = row_version + 1 where id = :id and status = 'DRAFT'")
                .setParameter("src", sourceVersionId)
                .setParameter("id", draftId)
                .executeUpdate();
    }

    @Override
    @Transactional
    public LayoutVersion publish(LayoutVersion draft, String publishedBy, String note, String reviewedBy) {
        // the port row serialises publishers, so two of them never both see their draft as the next published one
        Object portId = em.createNativeQuery("select id from ref.port where code = :code for update")
                .setParameter("code", draft.portCode())
                .getSingleResult();
        long port = ((Number) portId).longValue();
        em.createNativeQuery("update layout.layout_version set status = 'SUPERSEDED', row_version = row_version + 1 "
                        + "where port_id = :port and status = 'PUBLISHED'")
                .setParameter("port", port)
                .executeUpdate();
        int changed = em.createNativeQuery("update layout.layout_version set status = 'PUBLISHED', "
                        + "published_by = :by, published_at = now(), note = :note, reviewed_by = :reviewer, "
                        + "row_version = row_version + 1 where id = :id and status = 'DRAFT'")
                .setParameter("by", publishedBy)
                .setParameter("note", note)
                .setParameter("reviewer", reviewedBy)
                .setParameter("id", draft.id())
                .executeUpdate();
        if (changed != 1) {
            throw new ConflictException("The draft was already published or discarded");
        }
        em.createNativeQuery("update ref.port set current_published_layout_id = :id, updated_at = now() "
                        + "where id = :port")
                .setParameter("id", draft.id())
                .setParameter("port", port)
                .executeUpdate();
        return findVersion(draft.id()).orElseThrow();
    }

    @Override
    @Transactional
    public List<VersionSummary> listVersions(String portCode, Collection<LayoutStatus> statuses, int limit) {
        String sql = "select v.id, v.version_no, v.status, v.created_by, v.created_at, "
                + "v.published_by, v.published_at, v.reviewed_by, rf.version_no, v.note, "
                + "(select count(*) from layout.asset_revision r where r.layout_version_id = v.id) "
                + "from layout.layout_version v join ref.port p on p.id = v.port_id "
                + "left join layout.layout_version rf on rf.id = v.restored_from_version_id where p.code = ? "
                + statusFilter(statuses, "v.status") + "order by v.version_no desc limit ?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setString(i++, portCode);
            for (LayoutStatus s : statuses) {
                ps.setString(i++, s.name());
            }
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<VersionSummary> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new VersionSummary(
                            rs.getObject(1, UUID.class),
                            rs.getInt(2),
                            LayoutStatus.valueOf(rs.getString(3)),
                            rs.getString(4),
                            instant(rs, 5),
                            rs.getString(6),
                            instant(rs, 7),
                            rs.getString(8),
                            (Integer) rs.getObject(9),
                            rs.getString(10),
                            rs.getInt(11)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Reading the versions of " + portCode + " failed", e);
        }
    }

    @Override
    @Transactional
    public List<AssetHistoryEntry> assetHistory(UUID assetId, Collection<LayoutStatus> statuses) {
        String sql = "select v.id, v.version_no, v.status, r.name, GeometryType(r.geom), "
                + "ST_Area(r.geom::geography), ST_Length(r.geom::geography), "
                + "coalesce(r.updated_by, r.created_by), coalesce(r.updated_at, r.created_at) "
                + "from layout.asset_revision r join layout.layout_version v on v.id = r.layout_version_id "
                + "where r.asset_id = ? " + statusFilter(statuses, "v.status") + "order by v.version_no desc";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            ps.setObject(i++, assetId);
            for (LayoutStatus s : statuses) {
                ps.setString(i++, s.name());
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<AssetHistoryEntry> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new AssetHistoryEntry(
                            rs.getObject(1, UUID.class),
                            rs.getInt(2),
                            LayoutStatus.valueOf(rs.getString(3)),
                            rs.getString(4),
                            rs.getString(5),
                            rs.getDouble(6),
                            rs.getDouble(7),
                            rs.getString(8),
                            instant(rs, 9)));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Reading the history of asset " + assetId + " failed", e);
        }
    }

    @Override
    @Transactional
    public boolean assetExists(UUID assetId) {
        return ((Number) em.createNativeQuery("select count(*) from layout.asset where id = :id")
                                .setParameter("id", assetId)
                                .getSingleResult())
                        .longValue()
                > 0;
    }

    @Override
    @Transactional
    public int countAssets(UUID layoutId) {
        return ((Number)
                        em.createNativeQuery("select count(*) from layout.asset_revision where layout_version_id = :id")
                                .setParameter("id", layoutId)
                                .getSingleResult())
                .intValue();
    }

    /** "and status in (?, ?) " with one marker per status; an empty set matches nothing. */
    private static String statusFilter(Collection<LayoutStatus> statuses, String column) {
        if (statuses.isEmpty()) {
            return "and false ";
        }
        return "and " + column + " in (" + String.join(", ", Collections.nCopies(statuses.size(), "?")) + ") ";
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
            Map<UUID, AssetDetails> details = DetailStore.read(c, version.id(), only);
            var links = DetailStore.readLinks(c, version.id(), only);
            Map<UUID, AssetMeasures> measures = readMeasures(c, version.id(), only);
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

    private static Map<UUID, AssetMeasures> readMeasures(Connection c, UUID version, UUID only) throws SQLException {
        Map<UUID, AssetMeasures> out = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("select asset_id, area_m2, perimeter_m, length_m "
                + "from layout.asset_revision_measure where layout_version_id = ?"
                + (only == null ? "" : " and asset_id = ?"))) {
            ps.setObject(1, version);
            if (only != null) {
                ps.setObject(2, only);
            }
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
                            (Integer) rs.getObject(11),
                            rs.getString(12),
                            instant(rs, 13),
                            rs.getLong(14)));
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

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
