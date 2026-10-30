package com.djavafactory.castor.core.operations.application;

import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.operations.domain.ReconciliationItem;
import com.djavafactory.castor.core.operations.domain.ReconciliationKind;
import com.djavafactory.castor.core.operations.domain.ReconciliationRepository;
import com.djavafactory.castor.core.operations.domain.ReconciliationStatus;

/**
 * Opens review items for the projections, with the time taken from the injected clock (R8) so the age shown to an
 * operator follows the demo clock. A field-level item (a conflict) is not opened twice while one is still open for the
 * same entity and field: a source that keeps repeating a disagreeing value does not bury the queue.
 */
@ApplicationScoped
public class Reconciliations {

    private final ReconciliationRepository items;
    private final InstantSource clock;

    Reconciliations(ReconciliationRepository items, InstantSource clock) {
        this.items = items;
        this.clock = clock;
    }

    public ReconciliationItem open(
            ProjectionContext ctx,
            ReconciliationKind kind,
            String entityType,
            String entityId,
            Map<String, Object> detail) {
        return items.open(new ReconciliationItem(
                null,
                ctx.portId(),
                kind,
                entityType,
                entityId,
                ctx.eventId(),
                new LinkedHashMap<>(detail),
                ReconciliationStatus.OPEN,
                null,
                null,
                clock.instant(),
                null));
    }

    /** Like {@link #open} but only when no open item of the kind names this entity and field already. */
    public boolean openOnce(
            ProjectionContext ctx,
            ReconciliationKind kind,
            String entityType,
            String entityId,
            String field,
            Map<String, Object> detail) {
        if (items.hasOpen(ctx.portId(), kind, entityType, entityId, field)) {
            return false;
        }
        Map<String, Object> withField = new LinkedHashMap<>();
        withField.put("field", field);
        withField.putAll(detail);
        open(ctx, kind, entityType, entityId, withField);
        return true;
    }
}
