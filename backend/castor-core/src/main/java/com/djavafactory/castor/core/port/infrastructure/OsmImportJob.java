package com.djavafactory.castor.core.port.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import com.djavafactory.castor.core.port.domain.DataSourceCode;
import com.djavafactory.castor.core.port.domain.ReferenceFeature;
import com.djavafactory.castor.core.shared.domain.ResourceNotFoundException;
import com.djavafactory.castor.core.shared.domain.RuleViolation;
import com.djavafactory.castor.core.shared.domain.RuleViolationException;

/**
 * Loads a reference GeoJSON file into ref.reference_feature inside one ref.import_batch (kind OSM). Features are
 * upserted by (port, osm_ref), so running it twice gives the same rows. A source that may not be stored
 * (may_persist = false, for example GOOGLE) is refused before the file is read. The refresh procedure for the file
 * itself is in the README, section Data.
 */
@ApplicationScoped
public class OsmImportJob {

    private static final Logger LOG = Logger.getLogger(OsmImportJob.class);

    private static final String UPSERT = "insert into ref.reference_feature (port_id, kind, osm_ref, name, height_m, "
            + "width_m, tags, geom, source_code, accuracy_code, import_batch_id) values (?, ?, ?, ?, ?, ?, ?::jsonb, "
            + "ST_MakeValid(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)), ?, ?, ?) "
            + "on conflict (port_id, osm_ref) do update set kind = excluded.kind, name = excluded.name, "
            + "height_m = excluded.height_m, width_m = excluded.width_m, tags = excluded.tags, "
            + "geom = excluded.geom, source_code = excluded.source_code, accuracy_code = excluded.accuracy_code, "
            + "import_batch_id = excluded.import_batch_id";

    /** @param skipped true when the same file (same checksum) was already imported successfully */
    public record ImportResult(long batchId, int rowCount, String checksum, boolean skipped) {}

    private final DataSource dataSource;
    private final OsmFeatureParser parser;

    public OsmImportJob(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.parser = new OsmFeatureParser(mapper);
    }

    /** Imports a classpath resource, for example seed/westports/osm-reference.geojson. */
    public ImportResult run(String portCode, DataSourceCode source, String resource, boolean skipUnchanged) {
        byte[] file = read(resource);
        return run(portCode, source, file, skipUnchanged);
    }

    public ImportResult run(String portCode, DataSourceCode source, byte[] file, boolean skipUnchanged) {
        try (Connection c = dataSource.getConnection()) {
            long portId = portId(c, portCode);
            requirePersistable(c, source);
            String checksum = sha256(file);
            if (skipUnchanged) {
                Optional<ImportResult> done = lastSuccess(c, portId, source, checksum);
                if (done.isPresent()) {
                    return done.get();
                }
            }
            long batchId = startBatch(c, portId, source);
            try {
                List<ReferenceFeature> features = parser.parse(file, portCode);
                upsertAll(portId, batchId, source, features);
                finishBatch(c, batchId, "SUCCEEDED", features.size(), checksum, null);
                LOG.infof("Imported %d reference features for %s (batch %d)", features.size(), portCode, batchId);
                return new ImportResult(batchId, features.size(), checksum, false);
            } catch (IOException | SQLException | RuntimeException e) {
                finishBatch(c, batchId, "FAILED", null, checksum, truncate(String.valueOf(e.getMessage())));
                throw new IllegalStateException("Reference import failed: " + e.getMessage(), e);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Reference import failed", e);
        }
    }

    private void upsertAll(long portId, long batchId, DataSourceCode source, List<ReferenceFeature> features)
            throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(UPSERT)) {
                for (ReferenceFeature f : features) {
                    if (f.source() != source) {
                        throw new SQLException("Feature " + f.osmRef() + " has source " + f.source()
                                + " but the import is for " + source);
                    }
                    ps.setLong(1, portId);
                    ps.setString(2, f.kind().name());
                    ps.setString(3, f.osmRef());
                    ps.setString(4, f.name());
                    decimal(ps, 5, f.heightM());
                    decimal(ps, 6, f.widthM());
                    ps.setString(7, f.tagsJson());
                    ps.setString(8, f.geometryJson());
                    ps.setString(9, f.source().name());
                    ps.setString(10, f.accuracy().name());
                    ps.setLong(11, batchId);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    private static void decimal(PreparedStatement ps, int index, BigDecimal value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NUMERIC);
        } else {
            ps.setBigDecimal(index, value);
        }
    }

    private static long portId(Connection c, String portCode) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select id from ref.port where code = ?")) {
            ps.setString(1, portCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new ResourceNotFoundException("No port with code '" + portCode + "'");
                }
                return rs.getLong(1);
            }
        }
    }

    private static void requirePersistable(Connection c, DataSourceCode source) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select may_persist from ref.data_source where code = ?")) {
            ps.setString(1, source.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !rs.getBoolean(1)) {
                    throw new RuleViolationException(
                            "Data source not allowed",
                            List.of(new RuleViolation(
                                    "SOURCE_NOT_PERSISTABLE",
                                    null,
                                    "/source",
                                    "Source " + source + " may not be stored (its terms forbid keeping its data)")));
                }
            }
        }
    }

    private static Optional<ImportResult> lastSuccess(Connection c, long portId, DataSourceCode source, String checksum)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("select id, row_count from ref.import_batch where port_id = ? "
                + "and source_code = ? and kind = 'OSM' and status = 'SUCCEEDED' and checksum = ? "
                + "order by id desc limit 1")) {
            ps.setLong(1, portId);
            ps.setString(2, source.name());
            ps.setString(3, checksum);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? Optional.of(new ImportResult(rs.getLong(1), rs.getInt(2), checksum, true))
                        : Optional.empty();
            }
        }
    }

    private static long startBatch(Connection c, long portId, DataSourceCode source) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "insert into ref.import_batch (port_id, source_code, kind) values (?, ?, 'OSM') returning id")) {
            ps.setLong(1, portId);
            ps.setString(2, source.name());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void finishBatch(
            Connection c, long batchId, String status, Integer rows, String checksum, String note) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("update ref.import_batch set finished_at = now(), status = ?, "
                + "row_count = ?, checksum = ?, note = ? where id = ?")) {
            ps.setString(1, status);
            if (rows == null) {
                ps.setNull(2, Types.INTEGER);
            } else {
                ps.setInt(2, rows);
            }
            ps.setString(3, checksum);
            ps.setString(4, note);
            ps.setLong(5, batchId);
            ps.executeUpdate();
        }
    }

    private static byte[] read(String resource) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new ResourceNotFoundException("Reference file " + resource + " is not on the classpath");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + resource, e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    /** Whether a classpath file exists. */
    static boolean exists(String resource) {
        return Thread.currentThread().getContextClassLoader().getResource(resource) != null;
    }
}
