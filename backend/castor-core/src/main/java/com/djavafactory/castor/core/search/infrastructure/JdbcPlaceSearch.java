package com.djavafactory.castor.core.search.infrastructure;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.search.domain.PlaceHit;
import com.djavafactory.castor.core.search.domain.PlaceKind;
import com.djavafactory.castor.core.search.domain.PlaceSearch;

/**
 * One read-only query over the port, its control points, the assets of one layout version and the reference shapes.
 * The text is matched as a plain lower-case substring (strpos, so "%" and "_" are ordinary characters) and is only
 * ever a bind parameter.
 */
@ApplicationScoped
public class JdbcPlaceSearch implements PlaceSearch {

    /** rank: 0 the name or code is the text, 1 it starts with it, 2 it contains it. */
    private static final String RANK = "case when lower(%1$s) = q.t or lower(%2$s) = q.t then 0 "
            + "when strpos(lower(%1$s), q.t) = 1 or strpos(lower(%2$s), q.t) = 1 then 1 else 2 end";

    private static final String MATCH = "(strpos(lower(%1$s), q.t) > 0 or strpos(lower(%2$s), q.t) > 0)";

    private static final String SQL = "with q as (select ?::text as t, ?::bigint as port_id, ?::uuid as layout_id, "
            + "?::text as suffix) "
            + "select kind, id, label, sublabel, lon, lat, xmin, ymin, xmax, ymax from ("
            // the port
            + "select 0 as grp, " + RANK.formatted("p.name", "p.code") + " as rank, 'PORT' as kind, p.code as id, "
            + "p.name || q.suffix as label, 'Port' as sublabel, ST_X(p.origin) as lon, ST_Y(p.origin) as lat, "
            + "ST_XMin(p.extent) as xmin, ST_YMin(p.extent) as ymin, ST_XMax(p.extent) as xmax, "
            + "ST_YMax(p.extent) as ymax from ref.port p, q where p.id = q.port_id and "
            + MATCH.formatted("p.name", "p.code")
            // control points
            + " union all select 1, " + RANK.formatted("c.name", "c.code") + ", 'CONTROL_POINT', c.code, c.name, "
            + "'Control point', ST_X(c.geom), ST_Y(c.geom), ST_XMin(c.geom), ST_YMin(c.geom), ST_XMax(c.geom), "
            + "ST_YMax(c.geom) from ref.control_point c, q where c.port_id = q.port_id and "
            + MATCH.formatted("c.name", "c.code")
            // assets of the given layout version
            + " union all select 2, " + RANK.formatted("r.name", "a.code") + ", 'ASSET', a.id::text, r.name, t.label, "
            + "ST_X(r.anchor), ST_Y(r.anchor), ST_XMin(r.geom), ST_YMin(r.geom), ST_XMax(r.geom), ST_YMax(r.geom) "
            + "from layout.asset_revision r join layout.asset a on a.id = r.asset_id "
            + "join layout.asset_type t on t.code = a.type_code, q "
            + "where r.layout_version_id = q.layout_id and a.port_id = q.port_id and "
            + MATCH.formatted("r.name", "a.code")
            // read-only shapes of the background map
            + " union all select 3, " + RANK.formatted("f.name", "f.osm_ref") + ", 'REFERENCE', f.osm_ref, f.name, "
            + "initcap(lower(f.kind)) || ' (OpenStreetMap)', ST_X(ST_PointOnSurface(f.geom)), "
            + "ST_Y(ST_PointOnSurface(f.geom)), ST_XMin(f.geom), ST_YMin(f.geom), ST_XMax(f.geom), ST_YMax(f.geom) "
            + "from ref.reference_feature f, q where f.port_id = q.port_id and f.name is not null and "
            + MATCH.formatted("f.name", "f.osm_ref")
            + ") hits order by rank, grp, label, id limit ?";

    private final DataSource dataSource;

    public JdbcPlaceSearch(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<PlaceHit> find(long portId, UUID layoutId, String text, int limit) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(SQL)) {
            ps.setString(1, text);
            ps.setLong(2, portId);
            if (layoutId == null) {
                ps.setNull(3, Types.OTHER);
            } else {
                ps.setObject(3, layoutId);
            }
            ps.setString(4, PORT_LABEL_SUFFIX);
            ps.setInt(5, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<PlaceHit> hits = new ArrayList<>();
                while (rs.next()) {
                    hits.add(toHit(rs));
                }
                return hits;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Searching places failed", e);
        }
    }

    private static PlaceHit toHit(ResultSet rs) throws SQLException {
        return new PlaceHit(
                PlaceKind.valueOf(rs.getString("kind")),
                rs.getString("id"),
                rs.getString("label"),
                rs.getString("sublabel"),
                rs.getDouble("lon"),
                rs.getDouble("lat"),
                List.of(rs.getDouble("xmin"), rs.getDouble("ymin"), rs.getDouble("xmax"), rs.getDouble("ymax")));
    }
}
