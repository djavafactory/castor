package com.djavafactory.castor.core.operations.infrastructure;

import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.contracts.Quantity;
import com.djavafactory.castor.contracts.Unit;
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

    @Override
    public boolean isStorage(long portId, UUID assetId) {
        return sql.queryOne(
                        "select 1 as one from layout.asset a "
                                + "join layout.asset_type t on t.code = a.type_code and t.holds_stock "
                                + "join layout.asset_revision r on r.asset_id = a.id "
                                + "join layout.layout_version v on v.id = r.layout_version_id "
                                + "and v.status = 'PUBLISHED' where a.port_id = ? and a.id = ? limit 1",
                        r -> 1,
                        portId,
                        assetId)
                .isPresent();
    }

    @Override
    public Optional<Quantity> usableCapacity(long portId, UUID assetId) {
        return sql.queryOne(
                        "select d.capacity_value * d.usable_ratio as usable, d.capacity_unit "
                                + "from layout.storage_detail d "
                                + "join layout.layout_version v on v.id = d.layout_version_id "
                                + "and v.status = 'PUBLISHED' and v.port_id = ? where d.asset_id = ? limit 1",
                        r -> unit(r.text("capacity_unit"))
                                .map(u -> new Quantity(
                                        r.decimal("usable").setScale(3, java.math.RoundingMode.HALF_UP), u)),
                        portId,
                        assetId)
                .flatMap(capacity -> capacity);
    }

    /** The unit of an inspector capacity code; TEU and trucks are not units a balance is counted in. */
    private static Optional<Unit> unit(String code) {
        return switch (code) {
            case "t" -> Optional.of(Unit.TONNES);
            case "m3" -> Optional.of(Unit.CUBIC_METRES);
            case "pcs" -> Optional.of(Unit.PIECES);
            case "veh" -> Optional.of(Unit.VEHICLES);
            default -> Optional.empty();
        };
    }
}
