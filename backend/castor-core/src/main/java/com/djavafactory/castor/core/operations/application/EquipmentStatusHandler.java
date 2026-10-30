package com.djavafactory.castor.core.operations.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import jakarta.inject.Singleton;

import com.djavafactory.castor.contracts.EventType;
import com.djavafactory.castor.core.operations.domain.EquipmentRepository;
import com.djavafactory.castor.core.operations.domain.EquipmentState;
import com.djavafactory.castor.core.operations.domain.EquipmentStatus;
import com.djavafactory.castor.core.operations.domain.EquipmentStatusInterval;

/**
 * equipment.status.changed: sets status and reason, closes the open status interval and opens the next one. An event
 * older than the row leaves the row alone but is still written into the interval history at its true time.
 */
@Singleton
public class EquipmentStatusHandler extends EquipmentHandler {

    EquipmentStatusHandler(
            EquipmentRepository equipment, PublishedLayout layout, StaleEvents stale, StateChanges changes) {
        super(equipment, layout, stale, changes);
    }

    @Override
    public EventType type() {
        return EventType.EQUIPMENT_STATUS_CHANGED;
    }

    @Override
    void check(ProjectionContext ctx, PublishedLayout layout) {
        status(ctx);
    }

    @Override
    EquipmentState apply(ProjectionContext ctx, EquipmentState row) {
        return row.withStatus(status(ctx), reason(ctx));
    }

    @Override
    void applied(ProjectionContext ctx, EquipmentState before, EquipmentState saved) {
        Instant at = ctx.occurredAt();
        Optional<EquipmentStatusInterval> open = equipment.openInterval(ctx.portId(), saved.assetId());
        if (open.isPresent()) {
            EquipmentStatusInterval current = open.get();
            if (current.status() == saved.status() && Objects.equals(current.reason(), saved.statusReason())) {
                return;
            }
            // a source whose clock runs behind its own earlier event never gives an interval a negative length
            at = at.isBefore(current.startedAt()) ? current.startedAt() : at;
            equipment.closeInterval(ctx.portId(), saved.assetId(), at);
        }
        equipment.startInterval(interval(ctx, saved.assetId(), saved.status(), saved.statusReason(), at, null));
    }

    @Override
    void onStale(ProjectionContext ctx, EquipmentState current) {
        EquipmentStatus status = status(ctx);
        String reason = reason(ctx);
        Instant at = ctx.occurredAt();
        Optional<EquipmentStatusInterval> found = equipment.intervalAt(ctx.portId(), current.assetId(), at);
        if (found.isEmpty()) {
            return;
        }
        EquipmentStatusInterval inside = found.get();
        // The open interval is the machine's current status, which an older event must not replace; an interval that
        // already says this, or starts at this very instant, has nothing to learn
        if (inside.endedAt() == null
                || inside.startedAt().equals(at)
                || (inside.status() == status && Objects.equals(inside.reason(), reason))) {
            return;
        }
        equipment.endInterval(ctx.portId(), current.assetId(), inside.startedAt(), at);
        equipment.startInterval(interval(ctx, current.assetId(), status, reason, at, inside.endedAt()));
    }

    private static EquipmentStatusInterval interval(
            ProjectionContext ctx,
            java.util.UUID asset,
            EquipmentStatus status,
            String reason,
            Instant startedAt,
            Instant endedAt) {
        return new EquipmentStatusInterval(
                ctx.portId(),
                asset,
                status,
                reason,
                startedAt,
                endedAt,
                ctx.event().sourceSystem(),
                ctx.event().quality());
    }

    private static EquipmentStatus status(ProjectionContext ctx) {
        String value = text(ctx.event().payload(), "status");
        try {
            return EquipmentStatus.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw invalid("'" + value + "' is not an equipment status");
        }
    }

    private static String reason(ProjectionContext ctx) {
        return text(ctx.event().payload(), "reason");
    }
}
