package it.replica8.castor.core.port.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.replica8.castor.core.shared.application.DataSeedStep;

/**
 * Loads the Westports port record and its OpenStreetMap reference features (buildings and roads) from the classpath.
 * One transaction: either the whole seed is there or none of it.
 */
@ApplicationScoped
public class WestportsSeed implements DataSeedStep {

    static final String PORT_RESOURCE = "/seed/westports/port.json";
    static final String REFERENCE_RESOURCE = "/seed/westports/osm-reference.geojson";
    private static final String GENERIC_NAME_PREFIX = "Building ";

    private final DataSource dataSource;
    private final ObjectMapper json;

    public WestportsSeed(DataSource dataSource, ObjectMapper json) {
        this.dataSource = dataSource;
        this.json = json;
    }

    @Override
    public int seed() {
        JsonNode port = read(PORT_RESOURCE).path("port");
        JsonNode features = read(REFERENCE_RESOURCE).path("features");
        try (var c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                insertPort(c, port);
                for (JsonNode f : features) {
                    insertFeature(c, port.path("code").asText(), f);
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Seeding Westports failed", e);
        }
        return 0; // reference features are not assets
    }

    private void insertPort(Connection c, JsonNode port) throws SQLException {
        var sql = "insert into ref.port (code, name, time_zone, origin, frame_rot_deg, measure_srid, synthetic) "
                + "values (?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326), ?, ?, ?)";
        try (var ps = c.prepareStatement(sql)) {
            JsonNode origin = port.path("origin").path("coordinates");
            ps.setString(1, port.path("code").asText());
            ps.setString(2, port.path("name").asText());
            ps.setString(3, port.path("timeZone").asText());
            ps.setDouble(4, origin.get(0).asDouble());
            ps.setDouble(5, origin.get(1).asDouble());
            ps.setDouble(6, port.path("frameRotDeg").asDouble());
            ps.setInt(7, port.path("measureSrid").asInt());
            ps.setBoolean(8, port.path("synthetic").asBoolean(true));
            ps.executeUpdate();
        }
    }

    private void insertFeature(Connection c, String portCode, JsonNode f) throws SQLException {
        var sql = "insert into ref.reference_feature (port_code, osm_ref, kind, name, height_m, width_m, geom, "
                + "source, accuracy) values (?, ?, ?, ?, ?, ?, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), ?, ?)";
        JsonNode p = f.path("properties");
        try (var ps = c.prepareStatement(sql)) {
            ps.setString(1, portCode);
            ps.setString(2, p.path("osmRef").asText());
            ps.setString(3, p.path("kind").asText());
            ps.setString(4, name(p));
            ps.setObject(5, p.hasNonNull("heightM") ? p.get("heightM").decimalValue() : null);
            ps.setObject(6, p.hasNonNull("widthM") ? p.get("widthM").decimalValue() : null);
            ps.setString(7, f.path("geometry").toString());
            ps.setString(8, p.path("source").asText());
            ps.setString(9, p.path("accuracy").asText());
            ps.executeUpdate();
        }
    }

    /** The extract names unnamed buildings "Building <id>"; that is not a mapped name, the screen shows the id itself. */
    private static String name(JsonNode properties) {
        String name = properties.path("name").asText(null);
        return name == null || name.startsWith(GENERIC_NAME_PREFIX) ? null : name;
    }

    private JsonNode read(String resource) {
        try (InputStream in = WestportsSeed.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Seed file missing: " + resource);
            }
            return json.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Seed file unreadable: " + resource, e);
        }
    }
}
