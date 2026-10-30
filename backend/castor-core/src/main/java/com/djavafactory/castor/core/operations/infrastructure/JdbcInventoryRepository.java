package com.djavafactory.castor.core.operations.infrastructure;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.contracts.Quantity;
import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.core.operations.domain.InventoryAdjustment;
import com.djavafactory.castor.core.operations.domain.InventoryRepository;
import com.djavafactory.castor.core.operations.domain.MassBasis;
import com.djavafactory.castor.core.operations.domain.ObservedLevel;
import com.djavafactory.castor.core.operations.domain.StorageInventory;
import com.djavafactory.castor.core.shared.domain.ConflictException;

/** ops.storage_inventory and the immutable ops.inventory_adjustment. Runs in the transaction of the caller. */
@ApplicationScoped
public class JdbcInventoryRepository implements InventoryRepository {

    static final String SELECT = "select port_id, storage_asset_id, commodity, unit, quantity, "
            + "quantity_observed_at, source, quality, version, mass_basis, needs_reconciliation, level_quantity, "
            + "level_unit, level_at, level_source, level_quality, level_density_kg_m3, level_temperature_c "
            + "from ops.storage_inventory ";
    private static final String BY_KEY = "where port_id = ? and storage_asset_id = ? and commodity = ?";

    private final OpsSql sql;

    JdbcInventoryRepository(OpsSql sql) {
        this.sql = sql;
    }

    @Override
    public Optional<StorageInventory> find(long portId, UUID storageAssetId, String commodity) {
        return sql.queryOne(SELECT + BY_KEY, JdbcInventoryRepository::map, portId, storageAssetId, commodity);
    }

    @Override
    public Optional<StorageInventory> findForUpdate(long portId, UUID storageAssetId, String commodity) {
        return sql.queryOne(
                SELECT + BY_KEY + " for update", JdbcInventoryRepository::map, portId, storageAssetId, commodity);
    }

    @Override
    public List<StorageInventory> findByStorage(long portId, UUID storageAssetId) {
        return sql.query(
                SELECT + "where port_id = ? and storage_asset_id = ? order by commodity",
                JdbcInventoryRepository::map,
                portId,
                storageAssetId);
    }

    @Override
    public StorageInventory insert(StorageInventory i) {
        ObservedLevel l = i.level();
        sql.update(
                "insert into ops.storage_inventory (port_id, storage_asset_id, commodity, unit, quantity, "
                        + "quantity_observed_at, source, quality, version, mass_basis, needs_reconciliation, "
                        + "level_quantity, level_unit, level_at, level_source, level_quality, level_density_kg_m3, "
                        + "level_temperature_c) values (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                i.portId(),
                i.storageAssetId(),
                i.commodity(),
                unit(i),
                i.quantity() == null ? null : i.quantity().value(),
                i.quantityObservedAt(),
                i.source(),
                i.quality(),
                i.massBasis(),
                i.needsReconciliation(),
                l == null ? null : l.quantity().value(),
                l == null ? null : l.quantity().unit(),
                l == null ? null : l.observedAt(),
                l == null ? null : l.source(),
                l == null ? null : l.quality(),
                l == null ? null : l.densityKgM3(),
                l == null ? null : l.temperatureC());
        return withVersion(i, 0);
    }

    @Override
    public StorageInventory update(StorageInventory i) {
        ObservedLevel l = i.level();
        int rows = sql.update(
                "update ops.storage_inventory set unit = ?, quantity = ?, quantity_observed_at = ?, source = ?, "
                        + "quality = ?, mass_basis = ?, needs_reconciliation = ?, level_quantity = ?, level_unit = ?, "
                        + "level_at = ?, level_source = ?, level_quality = ?, level_density_kg_m3 = ?, "
                        + "level_temperature_c = ?, version = version + 1 " + BY_KEY + " and version = ?",
                unit(i),
                i.quantity() == null ? null : i.quantity().value(),
                i.quantityObservedAt(),
                i.source(),
                i.quality(),
                i.massBasis(),
                i.needsReconciliation(),
                l == null ? null : l.quantity().value(),
                l == null ? null : l.quantity().unit(),
                l == null ? null : l.observedAt(),
                l == null ? null : l.source(),
                l == null ? null : l.quality(),
                l == null ? null : l.densityKgM3(),
                l == null ? null : l.temperatureC(),
                i.portId(),
                i.storageAssetId(),
                i.commodity(),
                i.version());
        if (rows != 1) {
            throw new ConflictException("Inventory of " + i.commodity() + " changed since version " + i.version());
        }
        return withVersion(i, i.version() + 1);
    }

    /** The unit column is the book unit, or the unit of the first observation while no book exists yet. */
    private static Unit unit(StorageInventory i) {
        return i.quantity() != null ? i.quantity().unit() : i.level().quantity().unit();
    }

    @Override
    public boolean appendAdjustment(InventoryAdjustment a) {
        return sql.update(
                        "insert into ops.inventory_adjustment (port_id, storage_asset_id, commodity, delta, unit, "
                                + "reason, event_id, occurred_at) values (?, ?, ?, ?, ?, ?, ?, ?) "
                                + "on conflict (event_id) do nothing",
                        a.portId(),
                        a.storageAssetId(),
                        a.commodity(),
                        a.delta().value(),
                        a.delta().unit(),
                        a.reason(),
                        a.eventId(),
                        a.occurredAt())
                == 1;
    }

    @Override
    public List<InventoryAdjustment> adjustments(long portId, UUID storageAssetId) {
        return sql.query(
                "select port_id, storage_asset_id, commodity, delta, unit, reason, event_id, occurred_at "
                        + "from ops.inventory_adjustment where port_id = ? and storage_asset_id = ? "
                        + "order by occurred_at, id",
                r -> new InventoryAdjustment(
                        r.lng("port_id"),
                        r.uuid("storage_asset_id"),
                        r.text("commodity"),
                        new Quantity(r.decimal("delta"), Unit.parse(r.text("unit"))),
                        r.text("reason"),
                        r.uuid("event_id"),
                        r.instant("occurred_at")),
                portId,
                storageAssetId);
    }

    private static StorageInventory withVersion(StorageInventory i, long version) {
        return new StorageInventory(
                i.portId(),
                i.storageAssetId(),
                i.commodity(),
                i.quantity(),
                i.quantityObservedAt(),
                i.source(),
                i.quality(),
                version,
                i.massBasis(),
                i.needsReconciliation(),
                i.level());
    }

    static StorageInventory map(OpsSql.Row r) {
        BigDecimal book = r.decimal("quantity");
        String basis = r.text("mass_basis");
        ObservedLevel level = r.decimal("level_quantity") == null
                ? null
                : new ObservedLevel(
                        new Quantity(r.decimal("level_quantity"), Unit.parse(r.text("level_unit"))),
                        r.instant("level_at"),
                        r.text("level_source"),
                        Quality.valueOf(r.text("level_quality")),
                        r.decimal("level_density_kg_m3"),
                        r.decimal("level_temperature_c"));
        return new StorageInventory(
                r.lng("port_id"),
                r.uuid("storage_asset_id"),
                r.text("commodity"),
                book == null ? null : new Quantity(book, Unit.parse(r.text("unit"))),
                r.instant("quantity_observed_at"),
                r.text("source"),
                Quality.valueOf(r.text("quality")),
                r.lng("version"),
                basis == null ? null : MassBasis.valueOf(basis),
                r.bool("needs_reconciliation"),
                level);
    }
}
