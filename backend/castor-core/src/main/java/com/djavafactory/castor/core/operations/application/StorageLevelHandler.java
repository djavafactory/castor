package com.djavafactory.castor.core.operations.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Singleton;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.contracts.EventType;
import com.djavafactory.castor.contracts.Quantity;
import com.djavafactory.castor.core.operations.domain.InventoryRepository;
import com.djavafactory.castor.core.operations.domain.MassConversion;
import com.djavafactory.castor.core.operations.domain.ObservedLevel;
import com.djavafactory.castor.core.operations.domain.Provenance;
import com.djavafactory.castor.core.operations.domain.ReconciliationKind;
import com.djavafactory.castor.core.operations.domain.StorageInventory;

/**
 * storage.level.observed: what a tank or silo sensor reads is kept next to the book balance, never in place of it.
 * The mass of a volume is known only with the density the event carries (R3, R4); without one it stays UNKNOWN. When
 * the two can be compared and differ by more than {@value #TOLERANCE_PERCENT} %, a SOURCE_CONFLICT item opens (once
 * while it is open). A reading older than the one held changes nothing and opens a STALE_EVENT item.
 */
@Singleton
public class StorageLevelHandler implements EventHandler {

    /** The difference between the book balance and the measured level that is a conflict, in percent of the book. */
    static final int TOLERANCE_PERCENT = 2;

    private final InventoryRepository inventories;
    private final PublishedLayout layout;
    private final InventoryBalances balances;
    private final Reconciliations reconciliations;
    private final StaleEvents stale;

    StorageLevelHandler(
            InventoryRepository inventories,
            PublishedLayout layout,
            InventoryBalances balances,
            Reconciliations reconciliations,
            StaleEvents stale) {
        this.inventories = inventories;
        this.layout = layout;
        this.balances = balances;
        this.reconciliations = reconciliations;
        this.stale = stale;
    }

    @Override
    public EventType type() {
        return EventType.STORAGE_LEVEL_OBSERVED;
    }

    @Override
    public void handle(ProjectionContext ctx) {
        JsonNode p = ctx.event().payload();
        UUID storage = layout.requireStorage(ctx.portId(), Fields.required(p, "storageId"));
        String commodity = Fields.required(p, "commodity");
        BigDecimal density = Fields.optionalDecimal(p, "densityKgM3", 3);
        if (density != null && density.signum() <= 0) {
            throw Fields.invalid("densityKgM3 must be above zero");
        }
        ObservedLevel level = new ObservedLevel(
                new Quantity(Fields.quantity(p, "quantity"), Fields.unit(p)),
                ctx.occurredAt(),
                ctx.event().sourceSystem(),
                ctx.event().quality(),
                density,
                Fields.optionalDecimal(p, "temperatureC", 2));

        StorageInventory current =
                inventories.findForUpdate(ctx.portId(), storage, commodity).orElse(null);
        if (current != null
                && current.level() != null
                && ctx.occurredAt().isBefore(current.level().observedAt())) {
            ObservedLevel held = current.level();
            stale.open(
                    ctx,
                    StateChanges.INVENTORY,
                    storage + ":" + commodity,
                    new Provenance(held.source(), held.quality(), held.observedAt()),
                    null,
                    held.observedAt());
            return;
        }
        StorageInventory next = current == null
                ? new StorageInventory(
                        ctx.portId(),
                        storage,
                        commodity,
                        null,
                        ctx.occurredAt(),
                        ctx.event().sourceSystem(),
                        ctx.event().quality(),
                        0,
                        null,
                        false,
                        level)
                : current.withLevel(level);
        balances.store(ctx, current, next);
        compare(ctx, next, level);
    }

    private void compare(ProjectionContext ctx, StorageInventory row, ObservedLevel level) {
        Quantity book = row.quantity();
        if (book == null) {
            return;
        }
        BigDecimal observed;
        BigDecimal booked;
        String unit;
        if (book.unit() == level.quantity().unit()) {
            observed = level.quantity().value();
            booked = book.value();
            unit = book.unit().name();
        } else {
            // Different units meet only as masses, and only when a density says what the volume weighs
            Optional<BigDecimal> bookTonnes = MassConversion.tonnes(book, level.densityKgM3());
            Optional<BigDecimal> levelTonnes = MassConversion.tonnes(level.quantity(), level.densityKgM3());
            if (bookTonnes.isEmpty() || levelTonnes.isEmpty()) {
                return;
            }
            booked = bookTonnes.get();
            observed = levelTonnes.get();
            unit = "TONNES";
        }
        BigDecimal difference = observed.subtract(booked).abs();
        BigDecimal percentOfBook = difference.multiply(BigDecimal.valueOf(100));
        boolean conflict = booked.signum() == 0
                ? difference.signum() > 0
                : percentOfBook.compareTo(booked.multiply(BigDecimal.valueOf(TOLERANCE_PERCENT))) > 0;
        if (!conflict) {
            return;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("commodity", row.commodity());
        detail.put("storageAssetId", row.storageAssetId().toString());
        detail.put("book", booked);
        detail.put("observed", observed);
        detail.put("unit", unit);
        detail.put(
                "differencePercent",
                booked.signum() == 0 ? null : percentOfBook.divide(booked, 2, RoundingMode.HALF_UP));
        reconciliations.openOnce(
                ctx,
                ReconciliationKind.SOURCE_CONFLICT,
                StateChanges.INVENTORY,
                row.storageAssetId() + ":" + row.commodity(),
                "level",
                detail);
    }
}
