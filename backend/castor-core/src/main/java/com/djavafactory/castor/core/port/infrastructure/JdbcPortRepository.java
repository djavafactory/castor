package com.djavafactory.castor.core.port.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.geometry.domain.LonLat;
import com.djavafactory.castor.core.port.domain.BoundingBox;
import com.djavafactory.castor.core.port.domain.ControlPoint;
import com.djavafactory.castor.core.port.domain.DataSourceInfo;
import com.djavafactory.castor.core.port.domain.Port;
import com.djavafactory.castor.core.port.domain.PortRepository;
import com.djavafactory.castor.core.port.domain.PortSummary;
import com.djavafactory.castor.core.port.domain.ReferenceKind;

/** Reads ref.port and its children with plain SQL; PostGIS produces the GeoJSON. */
@ApplicationScoped
public class JdbcPortRepository implements PortRepository {

    private static final String PORT_SQL = "select id, code, name, city, time_zone, ST_X(origin), ST_Y(origin), "
            + "measure_srid, frame_rot_deg, ST_AsGeoJSON(extent, 7), ST_AsGeoJSON(land_extent, 7), "
            + "ST_AsGeoJSON(quay_line, 7), synthetic, current_published_layout_id from ref.port ";

    private static final String CONTROL_POINTS_SQL = "select jsonb_build_object('type', 'FeatureCollection', "
            + "'features', coalesce(jsonb_agg(jsonb_build_object('type', 'Feature', 'id', cp.code, "
            + "'geometry', ST_AsGeoJSON(cp.geom, 7)::jsonb, 'properties', jsonb_build_object('code', cp.code, "
            + "'name', cp.name, 'expected_local_x', cp.expected_local_x, 'expected_local_y', cp.expected_local_y, "
            + "'accuracy', cp.accuracy_code, 'source', cp.source_code)) order by cp.code), '[]'::jsonb))::text "
            + "from ref.control_point cp where cp.port_id = ?";

    private static final String CONTROL_POINT_ROWS_SQL =
            "select cp.code, cp.name, ST_X(cp.geom), ST_Y(cp.geom), cp.expected_local_x, cp.expected_local_y "
                    + "from ref.control_point cp where cp.port_id = ? order by cp.code";

    private static final String FEATURES_SQL = "select jsonb_build_object('type', 'FeatureCollection', "
            + "'features', coalesce(jsonb_agg(jsonb_build_object('type', 'Feature', 'id', rf.osm_ref, "
            + "'geometry', ST_AsGeoJSON(rf.geom, 7)::jsonb, 'properties', jsonb_build_object('kind', rf.kind, "
            + "'osm_ref', rf.osm_ref, 'name', rf.name, 'height_m', rf.height_m, 'width_m', rf.width_m, "
            + "'area_m2', case when ST_GeometryType(rf.geom) in ('ST_Polygon', 'ST_MultiPolygon') "
            + "then round(ST_Area(ST_Transform(rf.geom, p.measure_srid))::numeric, 2) end, "
            + "'source', rf.source_code, 'accuracy', rf.accuracy_code)) "
            + "order by rf.id), '[]'::jsonb))::text from ref.reference_feature rf "
            + "join ref.port p on p.id = rf.port_id where rf.port_id = ? and rf.kind = any(?)";

    private final JdbcQueries db;

    public JdbcPortRepository(DataSource dataSource) {
        this.db = new JdbcQueries(dataSource);
    }

    @Override
    public List<PortSummary> findAll() {
        return db.query(
                "select code, name from ref.port order by name",
                ps -> {},
                rs -> new PortSummary(rs.getString(1), rs.getString(2)));
    }

    @Override
    public Optional<Port> findByCode(String code) {
        return db.query(PORT_SQL + "where code = ?", ps -> ps.setString(1, code), JdbcPortRepository::toPort).stream()
                .findFirst();
    }

    @Override
    public List<DataSourceInfo> findDataSources() {
        return db.query(
                "select code, name, licence, may_trace, may_persist from ref.data_source order by code",
                ps -> {},
                rs -> new DataSourceInfo(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBoolean(5)));
    }

    @Override
    public int countPublishedBerths(Port port) {
        if (port.publishedLayoutVersionId() == null) {
            return 0;
        }
        return db.query(
                        "select count(*) from layout.asset_revision r join layout.asset a on a.id = r.asset_id "
                                + "where r.layout_version_id = ? and a.type_code = 'BERTH'",
                        ps -> ps.setObject(1, port.publishedLayoutVersionId()),
                        rs -> rs.getInt(1))
                .get(0);
    }

    @Override
    public String controlPointsGeoJson(long portId) {
        return db.single(CONTROL_POINTS_SQL, ps -> ps.setLong(1, portId));
    }

    @Override
    public List<ControlPoint> controlPoints(long portId) {
        return db.query(
                CONTROL_POINT_ROWS_SQL,
                ps -> ps.setLong(1, portId),
                rs -> new ControlPoint(
                        rs.getString(1),
                        rs.getString(2),
                        new LonLat(rs.getDouble(3), rs.getDouble(4)),
                        rs.getDouble(5),
                        rs.getDouble(6)));
    }

    @Override
    public String referenceFeaturesGeoJson(long portId, Set<ReferenceKind> kinds, BoundingBox box) {
        String sql = box == null ? FEATURES_SQL : FEATURES_SQL + " and rf.geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)";
        return db.single(sql, ps -> {
            ps.setLong(1, portId);
            Object[] names = kinds.stream().map(Enum::name).toArray();
            ps.setArray(2, ps.getConnection().createArrayOf("text", names));
            if (box != null) {
                ps.setDouble(3, box.minLon());
                ps.setDouble(4, box.minLat());
                ps.setDouble(5, box.maxLon());
                ps.setDouble(6, box.maxLat());
            }
        });
    }

    private static Port toPort(ResultSet rs) throws SQLException {
        return new Port(
                rs.getLong(1),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getString(5),
                new LonLat(rs.getDouble(6), rs.getDouble(7)),
                rs.getInt(8),
                rs.getDouble(9),
                rs.getString(10),
                rs.getString(11),
                rs.getString(12),
                rs.getBoolean(13),
                rs.getObject(14, UUID.class));
    }
}
