package com.djavafactory.castor.core.operations.infrastructure;

import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.operations.domain.PublishedAssets;

/** Reads the published layout; runs in the transaction of the caller. */
@ApplicationScoped
public class JdbcPublishedAssets implements PublishedAssets {

    private final OpsSql sql;

    JdbcPublishedAssets(OpsSql sql) {
        this.sql = sql;
    }

    @Override
    public Optional<String> typeCode(long portId, UUID assetId) {
        return sql.queryOne(
                "select a.type_code from layout.asset a "
                        + "join layout.asset_revision r on r.asset_id = a.id "
                        + "join layout.layout_version v on v.id = r.layout_version_id and v.status = 'PUBLISHED' "
                        + "where a.port_id = ? and a.id = ? limit 1",
                r -> r.text("type_code"),
                portId,
                assetId);
    }
}
