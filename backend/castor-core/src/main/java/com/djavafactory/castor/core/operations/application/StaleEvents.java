package com.djavafactory.castor.core.operations.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import com.djavafactory.castor.core.operations.domain.EventOrder;
import com.djavafactory.castor.core.operations.domain.Provenance;
import com.djavafactory.castor.core.operations.domain.ReconciliationItem;
import com.djavafactory.castor.core.operations.domain.ReconciliationKind;
import com.djavafactory.castor.core.operations.domain.ReconciliationRepository;
import com.djavafactory.castor.core.operations.domain.ReconciliationStatus;

/** The stale-event rule applied to a projection row, and the review item an older event opens. */
@ApplicationScoped
public class StaleEvents {

    private final ReconciliationRepository reconciliation;

    StaleEvents(ReconciliationRepository reconciliation) {
        this.reconciliation = reconciliation;
    }

    /** True when the event is older than the row that last changed with {@code rowProvenance}. */
    public boolean isStale(Provenance rowProvenance, Long rowSequence, Instant rowOccurredAt, ProjectionContext ctx) {
        return EventOrder.isStale(
                rowProvenance.source(),
                rowSequence,
                rowOccurredAt,
                ctx.event().sourceSystem(),
                ctx.event().sourceSequence(),
                ctx.occurredAt());
    }

    /** One STALE_EVENT item for the event; the processed-event guard makes sure an event never opens two. */
    public void open(
            ProjectionContext ctx,
            String entityType,
            String entityId,
            Provenance rowProvenance,
            Long rowSequence,
            Instant rowOccurredAt) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", ctx.event().type());
        detail.put("source", ctx.event().sourceSystem());
        detail.put("occurredAt", ctx.occurredAt().toString());
        detail.put("sourceSequence", ctx.event().sourceSequence());
        detail.put("currentSource", rowProvenance.source());
        detail.put("currentOccurredAt", rowOccurredAt.toString());
        detail.put("currentSourceSequence", rowSequence);
        reconciliation.open(new ReconciliationItem(
                null,
                ctx.portId(),
                ReconciliationKind.STALE_EVENT,
                entityType,
                entityId,
                ctx.eventId(),
                detail,
                ReconciliationStatus.OPEN,
                null,
                null));
    }
}
