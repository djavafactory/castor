package it.replica8.castor.core.port.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;

import it.replica8.castor.core.port.domain.ReferenceFeature;
import it.replica8.castor.core.port.domain.ReferenceFeatureRepository;

@ApplicationScoped
public class JdbcReferenceFeatureRepository implements ReferenceFeatureRepository {

    /** Areas are measured in the port's measurement CRS, never on degrees. */
    private static final String SELECT = "select f.osm_ref, f.kind, f.name, f.height_m, "
            + "case when ST_GeometryType(f.geom) in ('ST_Polygon', 'ST_MultiPolygon') "
            + "then ST_Area(ST_Transform(f.geom, p.measure_srid)) end, "
            + "f.source, f.accuracy, ST_AsGeoJSON(f.geom, 7) "
            + "from ref.reference_feature f join ref.port p on p.code = f.port_code "
            + "where f.port_code = lower(?) and f.kind = ? order by f.osm_ref";

    private final DataSource dataSource;

    public JdbcReferenceFeatureRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<ReferenceFeature> findByKind(String portCode, String kind) {
        try (var c = dataSource.getConnection();
                var ps = c.prepareStatement(SELECT)) {
            ps.setString(1, portCode);
            ps.setString(2, kind);
            try (var rs = ps.executeQuery()) {
                var found = new ArrayList<ReferenceFeature>();
                while (rs.next()) {
                    found.add(read(rs));
                }
                return found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Reading reference features of " + portCode + " failed", e);
        }
    }

    private static ReferenceFeature read(ResultSet rs) throws SQLException {
        return new ReferenceFeature(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                number(rs, 4),
                number(rs, 5),
                rs.getString(6),
                rs.getString(7),
                rs.getString(8));
    }

    private static Double number(ResultSet rs, int column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
