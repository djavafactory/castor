package com.djavafactory.castor.core.operations.application;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.inject.Singleton;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.contracts.EventType;
import com.djavafactory.castor.contracts.Quantity;
import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.core.operations.domain.CargoLot;
import com.djavafactory.castor.core.operations.domain.CargoMovement;
import com.djavafactory.castor.core.operations.domain.CargoRepository;
import com.djavafactory.castor.core.operations.domain.DeadLetterReason;
import com.djavafactory.castor.core.operations.domain.Provenance;
import com.djavafactory.castor.core.operations.domain.RejectedEventException;

/**
 * cargo.lot.moved: keeps the movement for good (ops.cargo_movement is immutable) and changes the balance of the storage
 * areas it touches by a delta: minus at {@code fromId}, plus at {@code toId}, each only when it is a storage area. The
 * balances do not depend on arrival order. The lot's location is another matter: it advances only to a movement that
 * is not older than the one that put it there, and an older movement leaves it where it is and opens a STALE_EVENT item
 * (an older movement never silently moves a lot back). A movement in another unit than its lot is dead-lettered
 * UNIT_MISMATCH: units are never converted.
 */
@Singleton
public class CargoLotMovedHandler implements EventHandler {

    private final CargoRepository cargo;
    private final PublishedLayout layout;
    private final InventoryBalances balances;
    private final StaleEvents stale;
    private final StateChanges changes;

    CargoLotMovedHandler(
            CargoRepository cargo,
            PublishedLayout layout,
            InventoryBalances balances,
            StaleEvents stale,
            StateChanges changes) {
        this.cargo = cargo;
        this.layout = layout;
        this.balances = balances;
        this.stale = stale;
        this.changes = changes;
    }

    @Override
    public EventType type() {
        return EventType.CARGO_LOT_MOVED;
    }

    @Override
    public void handle(ProjectionContext ctx) {
        JsonNode p = ctx.event().payload();
        String lotId = Fields.required(p, "lotId");
        CargoLot lot = cargo.findLot(ctx.portId(), lotId)
                .orElseThrow(() -> new RejectedEventException(
                        DeadLetterReason.UNKNOWN_ENTITY, "No cargo lot '" + lotId + "' is registered"));
        Unit unit = Fields.unit(p);
        if (unit != lot.unit()) {
            throw new RejectedEventException(
                    DeadLetterReason.UNIT_MISMATCH,
                    "Lot " + lotId + " is counted in " + lot.unit() + ", the movement is in " + unit);
        }
        BigDecimal value = Fields.quantity(p, "quantity");
        if (value.signum() <= 0) {
            throw Fields.invalid("quantity must be above zero at " + Fields.QUANTITY_SCALE + " decimals");
        }
        UUID from = layout.requireAsset(ctx.portId(), Fields.required(p, "fromId"));
        UUID to = layout.requireAsset(ctx.portId(), Fields.required(p, "toId"));
        UUID handledBy = Fields.text(p, "handledBy") == null
                ? null
                : layout.requireAsset(ctx.portId(), Fields.text(p, "handledBy"));
        Quantity moved = new Quantity(value, unit);

        if (!cargo.appendMovement(new CargoMovement(
                ctx.portId(), lot.id(), from, to, moved, handledBy, ctx.occurredAt(), ctx.eventId()))) {
            return;
        }
        applyBalances(ctx, lot, from, to, moved);

        if (lot.locationOccurredAt() != null && ctx.occurredAt().isBefore(lot.locationOccurredAt())) {
            stale.open(
                    ctx,
                    StateChanges.CARGO_LOT,
                    lot.id().toString(),
                    new Provenance(lot.source(), lot.quality(), lot.locationOccurredAt()),
                    null,
                    lot.locationOccurredAt());
            return;
        }
        CargoLot relocated = new CargoLot(
                lot.id(),
                lot.portId(),
                lot.lotCode(),
                lot.consignmentCode(),
                lot.cargoFamily(),
                lot.commodity(),
                lot.unit(),
                lot.registeredQuantity(),
                lot.massBasis(),
                to,
                ctx.occurredAt(),
                ctx.event().sourceSystem(),
                ctx.event().quality(),
                lot.version());
        changes.cargoLot(cargo.updateLot(relocated), ctx.occurredAt());
    }

    /** Both areas are locked in one order whatever the direction, so two movements never wait for each other. */
    private void applyBalances(ProjectionContext ctx, CargoLot lot, UUID from, UUID to, Quantity moved) {
        boolean out = layout.isStorage(ctx.portId(), from);
        boolean in = layout.isStorage(ctx.portId(), to);
        Quantity minus = new Quantity(moved.value().negate(), moved.unit());
        if (out && in && to.compareTo(from) < 0) {
            balances.move(ctx, to, lot.commodity(), moved, lot.massBasis());
            balances.move(ctx, from, lot.commodity(), minus, lot.massBasis());
            return;
        }
        if (out) {
            balances.move(ctx, from, lot.commodity(), minus, lot.massBasis());
        }
        if (in) {
            balances.move(ctx, to, lot.commodity(), moved, lot.massBasis());
        }
    }
}
