package com.djavafactory.castor.core.operations.application;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.core.operations.domain.DeadLetterReason;
import com.djavafactory.castor.core.operations.domain.EquipmentRepository;
import com.djavafactory.castor.core.operations.domain.EquipmentState;
import com.djavafactory.castor.core.operations.domain.RejectedEventException;

/**
 * What the three equipment events have in common: the machine must be in the published layout, an event older than the
 * row changes nothing but opens a review item, and every applied change is recorded with the DTO as its body. A
 * subclass says what the event changes in the row.
 */
abstract class EquipmentHandler implements EventHandler {

    protected final EquipmentRepository equipment;
    private final PublishedLayout layout;
    private final StaleEvents stale;
    private final StateChanges changes;

    EquipmentHandler(EquipmentRepository equipment, PublishedLayout layout, StaleEvents stale, StateChanges changes) {
        this.equipment = equipment;
        this.layout = layout;
        this.stale = stale;
        this.changes = changes;
    }

    @Override
    public void handle(ProjectionContext ctx) {
        UUID asset = layout.requireAsset(ctx.portId(), ctx.event().entityId());
        check(ctx, layout);
        EquipmentState current = equipment.find(ctx.portId(), asset).orElse(null);
        if (current != null
                && stale.isStale(current.provenance(), current.lastSourceSequence(), current.lastOccurredAt(), ctx)) {
            stale.open(
                    ctx,
                    StateChanges.EQUIPMENT,
                    asset.toString(),
                    current.provenance(),
                    current.lastSourceSequence(),
                    current.lastOccurredAt());
            onStale(ctx, current);
            return;
        }
        EquipmentState base = current == null
                ? new EquipmentState(
                        ctx.portId(), asset, null, null, null, null, ctx.provenance(), ctx.occurredAt(), null, 0)
                : current;
        EquipmentState next = apply(
                ctx,
                base.changedBy(ctx.provenance(), ctx.occurredAt(), ctx.event().sourceSequence()));
        EquipmentState saved = current == null ? equipment.insert(next) : equipment.update(next);
        applied(ctx, current, saved);
        changes.equipment(saved, ctx.occurredAt());
    }

    /** Rejects what can never be applied (a bad payload, a position outside the port) before any state is read. */
    void check(ProjectionContext ctx, PublishedLayout layout) {}

    /** The row with what the event says changed; {@code row} already carries the event's provenance and time. */
    abstract EquipmentState apply(ProjectionContext ctx, EquipmentState row);

    /** The event is older than the row: the row stays as it is, the history may still learn from it. */
    void onStale(ProjectionContext ctx, EquipmentState current) {}

    /** The row was stored; {@code before} is null for a machine seen for the first time. */
    void applied(ProjectionContext ctx, EquipmentState before, EquipmentState saved) {}

    static RejectedEventException invalid(String message) {
        return new RejectedEventException(DeadLetterReason.INVALID_EVENT, message);
    }

    static String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
