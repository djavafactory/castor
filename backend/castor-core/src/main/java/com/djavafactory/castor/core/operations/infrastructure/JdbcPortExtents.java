package com.djavafactory.castor.core.operations.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.operations.domain.PortExtents;

/** Checks a point against ref.port.extent; runs in the transaction of the caller. */
@ApplicationScoped
public class JdbcPortExtents implements PortExtents {

    private final OpsSql sql;

    JdbcPortExtents(OpsSql sql) {
        this.sql = sql;
    }

    @Override
    public boolean covers(long portId, double lon, double lat) {
        return sql.queryOne(
                        "select ST_Covers(extent, ST_SetSRID(ST_MakePoint(?::float8, ?::float8), 4326)) as inside "
                                + "from ref.port where id = ?",
                        r -> r.bool("inside"),
                        lon,
                        lat,
                        portId)
                .orElse(false);
    }
}
