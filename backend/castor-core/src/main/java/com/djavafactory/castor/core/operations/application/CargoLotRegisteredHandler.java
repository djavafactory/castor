package com.djavafactory.castor.core.operations.application;

import java.math.BigDecimal;
import java.util.Arrays;

import jakarta.inject.Singleton;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.contracts.EventType;
import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.core.operations.domain.CargoFamilyRepository;
import com.djavafactory.castor.core.operations.domain.CargoLot;
import com.djavafactory.castor.core.operations.domain.CargoRepository;
import com.djavafactory.castor.core.operations.domain.DeadLetterReason;
import com.djavafactory.castor.core.operations.domain.MassBasis;
import com.djavafactory.castor.core.operations.domain.RejectedEventException;

/**
 * cargo.lot.registered: creates the lot. The canonical id of a lot is the {@code entityId} of its events (for ITAP
 * {@code itap:<lotRef>}), and it is what a later {@code cargo.lot.moved} names as {@code lotId}. The family must be a
 * row of ref.cargo_family (R6: families are data). The location of a new lot is not part of its registration: it is
 * unknown until the first movement. Registering a lot again refreshes what the source says about it, and never
 * changes its unit.
 */
@Singleton
public class CargoLotRegisteredHandler implements EventHandler {

    private final CargoRepository cargo;
    private final CargoFamilyRepository families;
    private final StateChanges changes;

    CargoLotRegisteredHandler(CargoRepository cargo, CargoFamilyRepository families, StateChanges changes) {
        this.cargo = cargo;
        this.families = families;
        this.changes = changes;
    }

    @Override
    public EventType type() {
        return EventType.CARGO_LOT_REGISTERED;
    }

    @Override
    public void handle(ProjectionContext ctx) {
        JsonNode p = ctx.event().payload();
        String code = ctx.event().entityId() != null && !ctx.event().entityId().isBlank()
                ? ctx.event().entityId()
                : Fields.required(p, "lotCode");
        String family = Fields.required(p, "cargoFamily");
        if (families.find(family).isEmpty()) {
            throw new RejectedEventException(
                    DeadLetterReason.UNKNOWN_ENTITY, "No cargo family '" + family + "' in ref.cargo_family");
        }
        Unit unit = Fields.unit(p);
        BigDecimal quantity = Fields.quantity(p, "quantity");
        MassBasis basis = basis(p);
        String commodity = Fields.required(p, "commodity");
        String consignment = Fields.text(p, "consignmentCode");

        CargoLot current = cargo.findLot(ctx.portId(), code).orElse(null);
        if (current == null) {
            CargoLot created = cargo.insertLot(new CargoLot(
                    null,
                    ctx.portId(),
                    code,
                    consignment,
                    family,
                    commodity,
                    unit,
                    quantity,
                    basis,
                    null,
                    null,
                    ctx.event().sourceSystem(),
                    ctx.event().quality(),
                    0));
            changes.cargoLot(created, ctx.occurredAt());
            return;
        }
        if (current.unit() != unit) {
            throw new RejectedEventException(
                    DeadLetterReason.UNIT_MISMATCH,
                    "Lot " + code + " is counted in " + current.unit() + ", not " + unit);
        }
        CargoLot merged = new CargoLot(
                current.id(),
                current.portId(),
                current.lotCode(),
                consignment != null ? consignment : current.consignmentCode(),
                family,
                commodity,
                current.unit(),
                quantity,
                basis != null ? basis : current.massBasis(),
                current.currentLocationId(),
                current.locationOccurredAt(),
                ctx.event().sourceSystem(),
                ctx.event().quality(),
                current.version());
        if (Arrays.equals(strip(merged), strip(current))) {
            return;
        }
        changes.cargoLot(cargo.updateLot(merged), ctx.occurredAt());
    }

    /** The lot without its provenance and version, to see whether the event told anything new. */
    private static Object[] strip(CargoLot l) {
        return new Object[] {l.consignmentCode(), l.cargoFamily(), l.commodity(), l.registeredQuantity(), l.massBasis()
        };
    }

    private static MassBasis basis(JsonNode payload) {
        String value = Fields.text(payload, "massBasis");
        if (value == null) {
            return null;
        }
        try {
            return MassBasis.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw Fields.invalid("'" + value + "' is not a mass basis");
        }
    }
}
