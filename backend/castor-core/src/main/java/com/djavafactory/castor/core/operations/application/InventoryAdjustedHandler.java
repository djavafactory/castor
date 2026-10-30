package com.djavafactory.castor.core.operations.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Singleton;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.contracts.EventType;
import com.djavafactory.castor.contracts.Quantity;
import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.core.operations.domain.InventoryRepository;
import com.djavafactory.castor.core.operations.domain.StorageInventory;

/**
 * inventory.adjusted: an explicit correction of a balance. It becomes an immutable adjustment row with its reason and
 * changes the balance by the signed delta; nothing is ever overwritten silently. The commodity is the one the event
 * names; an event that does not name one may only mean the single commodity the area holds in that unit. A correction
 * that would take the balance below zero is refused (dead letter) rather than clamped.
 */
@Singleton
public class InventoryAdjustedHandler implements EventHandler {

    private final InventoryRepository inventories;
    private final PublishedLayout layout;
    private final InventoryBalances balances;

    InventoryAdjustedHandler(InventoryRepository inventories, PublishedLayout layout, InventoryBalances balances) {
        this.inventories = inventories;
        this.layout = layout;
        this.balances = balances;
    }

    @Override
    public EventType type() {
        return EventType.INVENTORY_ADJUSTED;
    }

    @Override
    public void handle(ProjectionContext ctx) {
        JsonNode p = ctx.event().payload();
        UUID storage = layout.requireStorage(ctx.portId(), Fields.required(p, "storageId"));
        Unit unit = Fields.unit(p);
        BigDecimal delta = Fields.quantity(p, "delta");
        if (delta.signum() == 0) {
            throw Fields.invalid("delta must not be zero");
        }
        String reason = Fields.required(p, "reason");
        String commodity =
                Fields.text(p, "commodity") != null ? Fields.text(p, "commodity") : onlyCommodity(ctx, storage, unit);
        balances.adjust(ctx, storage, commodity, new Quantity(delta, unit), reason);
    }

    private String onlyCommodity(ProjectionContext ctx, UUID storage, Unit unit) {
        List<String> held = inventories.findByStorage(ctx.portId(), storage).stream()
                .filter(i -> counted(i, unit))
                .map(StorageInventory::commodity)
                .toList();
        if (held.size() != 1) {
            throw Fields.invalid("commodity is required: the area holds " + held.size() + " commodities in " + unit);
        }
        return held.get(0);
    }

    private static boolean counted(StorageInventory i, Unit unit) {
        return i.quantity() != null && i.quantity().unit() == unit;
    }
}
