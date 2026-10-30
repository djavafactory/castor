package com.djavafactory.castor.core.operations.application;

import java.time.Instant;
import java.util.UUID;

import jakarta.inject.Singleton;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.contracts.EventType;
import com.djavafactory.castor.core.operations.domain.GateVisit;
import com.djavafactory.castor.core.operations.domain.GateVisitRepository;
import com.djavafactory.castor.core.operations.domain.Provenance;

/**
 * gate.transaction: IN sets {@code in_at} of the truck visit at the gate, OUT sets {@code out_at}. An OUT that no IN
 * came before makes a visit whose {@code in_at} is unknown (null), never a guessed time, and a later IN fills it. A
 * transaction older than the one of the same direction already held changes nothing and opens a STALE_EVENT item; an
 * exit before the entry is impossible and is refused. A visit is open from its IN until its OUT.
 */
@Singleton
public class GateTransactionHandler implements EventHandler {

    private final GateVisitRepository visits;
    private final PublishedLayout layout;
    private final StaleEvents stale;
    private final StateChanges changes;

    GateTransactionHandler(
            GateVisitRepository visits, PublishedLayout layout, StaleEvents stale, StateChanges changes) {
        this.visits = visits;
        this.layout = layout;
        this.stale = stale;
        this.changes = changes;
    }

    @Override
    public EventType type() {
        return EventType.GATE_TRANSACTION;
    }

    @Override
    public void handle(ProjectionContext ctx) {
        JsonNode p = ctx.event().payload();
        String truckVisit = Fields.required(p, "truckVisitId");
        UUID gate = layout.requireGate(ctx.portId(), Fields.required(p, "gateId"));
        boolean in = direction(p);
        String lane = Fields.text(p, "lane");
        Instant at = ctx.occurredAt();

        GateVisit current = visits.find(ctx.portId(), truckVisit, gate).orElse(null);
        Instant inAt = current == null ? null : current.inAt();
        Instant outAt = current == null ? null : current.outAt();
        Instant held = in ? inAt : outAt;
        if (held != null && at.isBefore(held)) {
            stale.open(
                    ctx,
                    StateChanges.GATE_VISIT,
                    truckVisit + ":" + gate,
                    new Provenance(current.source(), current.quality(), held),
                    null,
                    held);
            return;
        }
        if (in) {
            inAt = at;
        } else {
            outAt = at;
        }
        if (inAt != null && outAt != null && outAt.isBefore(inAt)) {
            throw Fields.invalid(
                    "Truck visit " + truckVisit + " would leave at " + outAt + ", before it entered at " + inAt);
        }
        GateVisit saved = visits.save(new GateVisit(
                ctx.portId(),
                truckVisit,
                gate,
                inAt,
                outAt,
                lane != null ? lane : current == null ? null : current.lane(),
                ctx.event().sourceSystem(),
                ctx.event().quality()));
        changes.gateVisit(saved, at);
    }

    private static boolean direction(JsonNode payload) {
        String value = Fields.required(payload, "direction");
        return switch (value) {
            case "IN" -> true;
            case "OUT" -> false;
            default -> throw Fields.invalid("'" + value + "' is not a gate direction");
        };
    }
}
