package com.djavafactory.castor.core.operations.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.contracts.Quantity;
import com.djavafactory.castor.core.operations.domain.DeadLetterReason;
import com.djavafactory.castor.core.operations.domain.InventoryAdjustment;
import com.djavafactory.castor.core.operations.domain.InventoryRepository;
import com.djavafactory.castor.core.operations.domain.MassBasis;
import com.djavafactory.castor.core.operations.domain.NegativeBalanceException;
import com.djavafactory.castor.core.operations.domain.ReconciliationKind;
import com.djavafactory.castor.core.operations.domain.RejectedEventException;
import com.djavafactory.castor.core.operations.domain.StorageInventory;

/**
 * The book balance of a storage area, changed by deltas only: a movement or an adjustment adds a signed quantity to
 * the row, so the order in which different movements arrive does not change the result. What cannot be applied
 * honestly is not forced: a balance that would go below zero stays as it is and its row is flagged, a balance above the
 * usable capacity is kept as reported, and each opens an item for a person. Every changed row is recorded as a state
 * change of the transaction.
 */
@ApplicationScoped
public class InventoryBalances {

    private final InventoryRepository inventories;
    private final PublishedLayout layout;
    private final Reconciliations reconciliations;
    private final StateChanges changes;

    InventoryBalances(
            InventoryRepository inventories,
            PublishedLayout layout,
            Reconciliations reconciliations,
            StateChanges changes) {
        this.inventories = inventories;
        this.layout = layout;
        this.reconciliations = reconciliations;
        this.changes = changes;
    }

    /** The effect of one movement on one storage area; {@code delta} is negative for the area the lot left. */
    public void move(ProjectionContext ctx, UUID storage, String commodity, Quantity delta, MassBasis basis) {
        StorageInventory current =
                inventories.findForUpdate(ctx.portId(), storage, commodity).orElse(null);
        requireSameUnitAndBasis(current, delta, basis);
        StorageInventory base = current != null ? current : empty(ctx, storage, commodity, delta);
        StorageInventory next;
        try {
            next = base.adjustedBy(
                    delta,
                    latest(base, ctx),
                    ctx.event().sourceSystem(),
                    ctx.event().quality());
        } catch (NegativeBalanceException e) {
            refuse(ctx, current, base, storage, commodity, delta);
            return;
        }
        if (basis != null && next.massBasis() == null) {
            next = next.withMassBasis(basis);
        }
        store(ctx, current, next);
        checkCapacity(ctx, next, delta);
    }

    /**
     * A correction: an immutable adjustment row and the balance change it makes. It clears the review flag, because it
     * is the explicit act that settles a doubtful balance. One that would take the balance below zero is refused.
     */
    public void adjust(ProjectionContext ctx, UUID storage, String commodity, Quantity delta, String reason) {
        StorageInventory current =
                inventories.findForUpdate(ctx.portId(), storage, commodity).orElse(null);
        requireSameUnitAndBasis(current, delta, null);
        StorageInventory base = current != null ? current : empty(ctx, storage, commodity, delta);
        StorageInventory next;
        try {
            next = base.adjustedBy(
                            delta,
                            latest(base, ctx),
                            ctx.event().sourceSystem(),
                            ctx.event().quality())
                    .withNeedsReconciliation(false);
        } catch (NegativeBalanceException e) {
            throw new RejectedEventException(DeadLetterReason.INVALID_EVENT, e.getMessage());
        }
        inventories.appendAdjustment(new InventoryAdjustment(
                ctx.portId(), storage, commodity, delta, reason, ctx.eventId(), ctx.occurredAt()));
        store(ctx, current, next);
        checkCapacity(ctx, next, delta);
    }

    /** Stores the row and records it as a state change; {@code current} is null for a row seen for the first time. */
    StorageInventory store(ProjectionContext ctx, StorageInventory current, StorageInventory next) {
        StorageInventory saved = current == null ? inventories.insert(next) : inventories.update(next);
        changes.inventory(saved, ctx.occurredAt());
        return saved;
    }

    private void refuse(
            ProjectionContext ctx,
            StorageInventory current,
            StorageInventory base,
            UUID storage,
            String commodity,
            Quantity delta) {
        if (current == null || !current.needsReconciliation()) {
            store(ctx, current, base.withNeedsReconciliation(true));
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("commodity", commodity);
        detail.put("storageAssetId", storage.toString());
        detail.put("unit", delta.unit().name());
        detail.put(
                "balance",
                base.quantity() == null ? BigDecimal.ZERO : base.quantity().value());
        detail.put("delta", delta.value());
        reconciliations.open(
                ctx, ReconciliationKind.NEGATIVE_BALANCE, StateChanges.INVENTORY, storage + ":" + commodity, detail);
    }

    /** More stock than the usable capacity: kept as reported, and a person is told. Only a delta that adds can cause it. */
    private void checkCapacity(ProjectionContext ctx, StorageInventory row, Quantity delta) {
        if (delta.value().signum() <= 0) {
            return;
        }
        var capacity = layout.usableCapacity(ctx.portId(), row.storageAssetId()).orElse(null);
        if (capacity == null
                || row.quantity() == null
                || capacity.unit() != row.quantity().unit()) {
            return;
        }
        BigDecimal stock = BigDecimal.ZERO;
        for (StorageInventory other : inventories.findByStorage(ctx.portId(), row.storageAssetId())) {
            if (other.quantity() != null && other.quantity().unit() == capacity.unit()) {
                stock = stock.add(other.quantity().value());
            }
        }
        if (stock.compareTo(capacity.value()) > 0) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("commodity", row.commodity());
            detail.put("storageAssetId", row.storageAssetId().toString());
            detail.put("unit", capacity.unit().name());
            detail.put("stock", stock);
            detail.put("capacity", capacity.value());
            reconciliations.open(
                    ctx,
                    ReconciliationKind.CAPACITY_EXCEEDED,
                    StateChanges.INVENTORY,
                    row.storageAssetId() + ":" + row.commodity(),
                    detail);
        }
    }

    /** Units are never converted (R4), and wet and dry tonnes are never added together. */
    private static void requireSameUnitAndBasis(StorageInventory current, Quantity delta, MassBasis basis) {
        if (current == null) {
            return;
        }
        if (current.quantity() != null && current.quantity().unit() != delta.unit()) {
            throw new RejectedEventException(
                    DeadLetterReason.UNIT_MISMATCH,
                    "The balance of " + current.commodity() + " is counted in "
                            + current.quantity().unit() + ", not " + delta.unit());
        }
        if (basis != null && current.massBasis() != null && current.massBasis() != basis) {
            throw new RejectedEventException(
                    DeadLetterReason.UNIT_MISMATCH,
                    "The balance of " + current.commodity() + " is " + current.massBasis() + " tonnes, a " + basis
                            + " lot cannot be added to it");
        }
    }

    private static StorageInventory empty(ProjectionContext ctx, UUID storage, String commodity, Quantity delta) {
        return new StorageInventory(
                ctx.portId(),
                storage,
                commodity,
                new Quantity(BigDecimal.ZERO.setScale(Fields.QUANTITY_SCALE), delta.unit()),
                ctx.occurredAt(),
                ctx.event().sourceSystem(),
                ctx.event().quality(),
                0);
    }

    /** The book is as recent as its latest change: a late movement does not move the time of the balance back. */
    private static Instant latest(StorageInventory base, ProjectionContext ctx) {
        return base.quantityObservedAt().isAfter(ctx.occurredAt()) ? base.quantityObservedAt() : ctx.occurredAt();
    }
}
