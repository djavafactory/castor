package com.djavafactory.castor.core.layout.infrastructure;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;

import com.djavafactory.castor.core.layout.domain.PlacementFacts;
import com.djavafactory.castor.core.shared.application.OperationMetrics;

/**
 * The spatial facts of the placement rules, answered by PostGIS. The pair query walks the GiST index on
 * layout.asset_revision (the {@code &&} test), so the cost follows the shapes near a candidate and not the size of the
 * layout. Shared areas are measured after ST_Transform to the measurement CRS of the port.
 */
@ApplicationScoped
public class PostgisPlacementFacts implements PlacementFacts {

    /** Id of a candidate that is not stored yet; no stored asset has it, so nothing is left out of the pairs. */
    private static final UUID NEW_ASSET = new UUID(0, 0);

    private static final String SUBJECT_OF_LAYOUT = "select r.asset_id, a.type_code, r.geom "
            + "from layout.asset_revision r join layout.asset a on a.id = r.asset_id where r.layout_version_id = ?";

    private static final String SUBJECT_OF_CANDIDATE =
            "select ?::uuid as asset_id, ?::text as type_code, ST_SetSRID(ST_GeomFromWKB(?), 4326) as geom";

    /** Which pairs matter is decided here; the thresholds are applied by LayoutValidator. */
    private static final String CONTACTS = "with subject as (%s) "
            + "select s.asset_id, o.asset_id, o.name, oa.type_code, "
            + "  case when ST_Dimension(s.geom) = 2 and ST_Dimension(o.geom) = 2 "
            + "       then ST_Area(ST_Transform(ST_Intersection(s.geom, o.geom), p.measure_srid)) else 0 end, "
            + "  not ST_Touches(s.geom, o.geom), "
            + "  coalesce((select array_agg(z.allowed_type_code) from layout.zone_allowed_type z "
            + "            where z.asset_id = o.asset_id and z.layout_version_id = o.layout_version_id), '{}') "
            + "from subject s "
            + "join layout.layout_version v on v.id = ? "
            + "join ref.port p on p.id = v.port_id "
            + "join layout.asset_type st on st.code = s.type_code "
            + "join layout.asset_revision o on o.layout_version_id = v.id and o.asset_id <> s.asset_id "
            + "     and o.geom && s.geom and ST_Intersects(o.geom, s.geom) "
            + "join layout.asset oa on oa.id = o.asset_id "
            + "join layout.asset_type ot on ot.code = oa.type_code "
            + "where (st.is_solid and ot.is_solid) "
            + "   or ot.code = '" + EXCLUSION_ZONE + "' "
            + "   or (st.code in (" + quoted(BLOCK_TYPES) + ") and ot.code in (" + quoted(ROAD_TYPES) + ")) "
            + "order by s.asset_id, o.name";

    private static final String BERTHS = "select r.asset_id, r.name, ST_AsBinary(r.geom) "
            + "from layout.asset_revision r join layout.asset a on a.id = r.asset_id "
            + "join layout.asset_type t on t.code = a.type_code "
            + "where r.layout_version_id = ? and t.geometry_kind = 'QUAY_SEGMENT' order by r.name";

    private final DataSource dataSource;
    private final OperationMetrics metrics;

    public PostgisPlacementFacts(DataSource dataSource, OperationMetrics metrics) {
        this.dataSource = dataSource;
        this.metrics = metrics;
    }

    @Override
    public List<Contact> contacts(UUID layoutVersionId, Candidate candidate) {
        return timed(() -> queryContacts(layoutVersionId, candidate));
    }

    @Override
    public List<Berth> berths(UUID layoutVersionId) {
        return timed(() -> queryBerths(layoutVersionId));
    }

    /** Runs a spatial query and reports how long it took (castor_spatial_query_duration). */
    private <T> T timed(Supplier<T> query) {
        long start = System.nanoTime();
        try {
            return query.get();
        } finally {
            metrics.spatialQueryFinished(Duration.ofNanos(System.nanoTime() - start));
        }
    }

    private List<Contact> queryContacts(UUID layoutVersionId, Candidate candidate) {
        try (Connection c = dataSource.getConnection()) {
            if (candidate == null) {
                try (PreparedStatement ps = c.prepareStatement(CONTACTS.formatted(SUBJECT_OF_LAYOUT))) {
                    ps.setObject(1, layoutVersionId);
                    ps.setObject(2, layoutVersionId);
                    return read(ps);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(CONTACTS.formatted(SUBJECT_OF_CANDIDATE))) {
                ps.setObject(1, candidate.id() == null ? NEW_ASSET : candidate.id());
                ps.setString(2, candidate.typeCode());
                ps.setBytes(3, new org.locationtech.jts.io.WKBWriter().write(candidate.geom()));
                ps.setObject(4, layoutVersionId);
                return read(ps);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Reading the neighbours of a shape failed", e);
        }
    }

    private List<Berth> queryBerths(UUID layoutVersionId) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(BERTHS)) {
            ps.setObject(1, layoutVersionId);
            try (ResultSet rs = ps.executeQuery()) {
                var out = new ArrayList<Berth>();
                while (rs.next()) {
                    out.add(new Berth(rs.getObject(1, UUID.class), rs.getString(2), geometry(rs.getBytes(3))));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Reading the berths failed", e);
        }
    }

    private static List<Contact> read(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            var out = new ArrayList<Contact>();
            while (rs.next()) {
                Array allowed = rs.getArray(7);
                out.add(new Contact(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getDouble(5),
                        rs.getBoolean(6),
                        List.of((String[]) allowed.getArray())));
            }
            return out;
        }
    }

    private static Geometry geometry(byte[] wkb) {
        try {
            Geometry g = new WKBReader().read(wkb);
            g.setSRID(4326);
            return g;
        } catch (ParseException e) {
            throw new IllegalStateException("Unreadable geometry from the database", e);
        }
    }

    private static String quoted(java.util.Set<String> codes) {
        return codes.stream()
                .sorted()
                .map(code -> "'" + code + "'")
                .reduce((a, b) -> a + ", " + b)
                .orElse("''");
    }
}
