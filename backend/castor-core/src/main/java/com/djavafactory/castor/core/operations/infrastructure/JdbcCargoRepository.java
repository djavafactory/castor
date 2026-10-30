package com.djavafactory.castor.core.operations.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.contracts.Quantity;
import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.core.operations.domain.CargoLot;
import com.djavafactory.castor.core.operations.domain.CargoMovement;
import com.djavafactory.castor.core.operations.domain.CargoRepository;
import com.djavafactory.castor.core.operations.domain.MassBasis;
import com.djavafactory.castor.core.shared.domain.ConflictException;

/** ops.cargo_lot and the immutable ops.cargo_movement. Runs in the transaction of the caller. */
@ApplicationScoped
public class JdbcCargoRepository implements CargoRepository {

    private static final String LOT_SELECT = "select id, port_id, lot_code, consignment_code, cargo_family, commodity, "
            + "unit, registered_quantity, mass_basis, current_location_id, location_occurred_at, source, quality, "
            + "version from ops.cargo_lot ";

    private final OpsSql sql;

    JdbcCargoRepository(OpsSql sql) {
        this.sql = sql;
    }

    @Override
    public Optional<CargoLot> findLot(long portId, String lotCode) {
        return sql.queryOne(
                LOT_SELECT + "where port_id = ? and lot_code = ?", JdbcCargoRepository::mapLot, portId, lotCode);
    }

    @Override
    public Optional<CargoLot> findLot(UUID id) {
        return sql.queryOne(LOT_SELECT + "where id = ?", JdbcCargoRepository::mapLot, id);
    }

    @Override
    public CargoLot insertLot(CargoLot l) {
        UUID id = l.id() == null ? UUID.randomUUID() : l.id();
        sql.update(
                "insert into ops.cargo_lot (id, port_id, lot_code, consignment_code, cargo_family, commodity, unit, "
                        + "registered_quantity, mass_basis, current_location_id, location_occurred_at, source, quality, "
                        + "version) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                id,
                l.portId(),
                l.lotCode(),
                l.consignmentCode(),
                l.cargoFamily(),
                l.commodity(),
                l.unit(),
                l.registeredQuantity(),
                l.massBasis(),
                l.currentLocationId(),
                l.locationOccurredAt(),
                l.source(),
                l.quality());
        return withIdAndVersion(l, id, 0);
    }

    @Override
    public CargoLot updateLot(CargoLot l) {
        int rows = sql.update(
                "update ops.cargo_lot set consignment_code = ?, cargo_family = ?, commodity = ?, unit = ?, "
                        + "registered_quantity = ?, mass_basis = ?, current_location_id = ?, location_occurred_at = ?, "
                        + "source = ?, quality = ?, version = version + 1 where id = ? and version = ?",
                l.consignmentCode(),
                l.cargoFamily(),
                l.commodity(),
                l.unit(),
                l.registeredQuantity(),
                l.massBasis(),
                l.currentLocationId(),
                l.locationOccurredAt(),
                l.source(),
                l.quality(),
                l.id(),
                l.version());
        if (rows != 1) {
            throw new ConflictException("Cargo lot " + l.lotCode() + " changed since version " + l.version());
        }
        return withIdAndVersion(l, l.id(), l.version() + 1);
    }

    @Override
    public boolean appendMovement(CargoMovement m) {
        return sql.update(
                        "insert into ops.cargo_movement (port_id, lot_id, from_id, to_id, quantity, unit, handled_by, "
                                + "occurred_at, event_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                + "on conflict (event_id) do nothing",
                        m.portId(),
                        m.lotId(),
                        m.fromId(),
                        m.toId(),
                        m.quantity().value(),
                        m.quantity().unit(),
                        m.handledBy(),
                        m.occurredAt(),
                        m.eventId())
                == 1;
    }

    @Override
    public List<CargoMovement> movements(UUID lotId) {
        return sql.query(
                "select port_id, lot_id, from_id, to_id, quantity, unit, handled_by, occurred_at, event_id "
                        + "from ops.cargo_movement where lot_id = ? order by occurred_at, id",
                r -> new CargoMovement(
                        r.lng("port_id"),
                        r.uuid("lot_id"),
                        r.uuid("from_id"),
                        r.uuid("to_id"),
                        new Quantity(r.decimal("quantity"), Unit.parse(r.text("unit"))),
                        r.uuid("handled_by"),
                        r.instant("occurred_at"),
                        r.uuid("event_id")),
                lotId);
    }

    private static CargoLot withIdAndVersion(CargoLot l, UUID id, long version) {
        return new CargoLot(
                id,
                l.portId(),
                l.lotCode(),
                l.consignmentCode(),
                l.cargoFamily(),
                l.commodity(),
                l.unit(),
                l.registeredQuantity(),
                l.massBasis(),
                l.currentLocationId(),
                l.locationOccurredAt(),
                l.source(),
                l.quality(),
                version);
    }

    private static CargoLot mapLot(OpsSql.Row r) {
        String basis = r.text("mass_basis");
        return new CargoLot(
                r.uuid("id"),
                r.lng("port_id"),
                r.text("lot_code"),
                r.text("consignment_code"),
                r.text("cargo_family"),
                r.text("commodity"),
                Unit.parse(r.text("unit")),
                r.decimal("registered_quantity"),
                basis == null ? null : MassBasis.valueOf(basis),
                r.uuid("current_location_id"),
                r.instant("location_occurred_at"),
                r.text("source"),
                Quality.valueOf(r.text("quality")),
                r.lng("version"));
    }
}
