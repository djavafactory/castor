package com.djavafactory.castor.core.operations.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.inject.Singleton;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.contracts.EventType;
import com.djavafactory.castor.core.operations.domain.DeadLetterReason;
import com.djavafactory.castor.core.operations.domain.Provenance;
import com.djavafactory.castor.core.operations.domain.RejectedEventException;
import com.djavafactory.castor.core.operations.domain.VesselCall;
import com.djavafactory.castor.core.operations.domain.VesselCallRepository;
import com.djavafactory.castor.core.operations.domain.VesselStatus;

/**
 * vessel.call.updated: creates or updates the call named by callCode. A field the event does not carry stays as it was
 * (an EDI message knows milestones only), and the berth must be a BERTH of the published layout. A call first seen
 * without a status gets the one its milestones imply.
 */
@Singleton
public class VesselCallHandler implements EventHandler {

    private final VesselCallRepository calls;
    private final PublishedLayout layout;
    private final StaleEvents stale;
    private final StateChanges changes;

    VesselCallHandler(VesselCallRepository calls, PublishedLayout layout, StaleEvents stale, StateChanges changes) {
        this.calls = calls;
        this.layout = layout;
        this.stale = stale;
        this.changes = changes;
    }

    @Override
    public EventType type() {
        return EventType.VESSEL_CALL_UPDATED;
    }

    @Override
    public void handle(ProjectionContext ctx) {
        JsonNode p = ctx.event().payload();
        String callCode = text(p, "callCode");
        UUID berth = p.hasNonNull("berthId") ? layout.requireBerth(ctx.portId(), text(p, "berthId")) : null;
        VesselStatus status = status(p);
        Instant eta = instant(p, "eta");
        Instant ata = instant(p, "ata");
        Instant etd = instant(p, "etd");
        Instant atd = instant(p, "atd");
        Long sequence = ctx.event().sourceSequence();
        Provenance provenance = ctx.provenance();
        VesselCall current = calls.find(ctx.portId(), callCode).orElse(null);

        if (current == null) {
            VesselCall created = new VesselCall(
                    null,
                    ctx.portId(),
                    callCode,
                    text(p, "vesselName"),
                    text(p, "imo"),
                    decimal(p, "loaM"),
                    decimal(p, "beamM"),
                    status != null ? status : implied(ata, atd),
                    eta,
                    ata,
                    etd,
                    atd,
                    berth,
                    provenance,
                    ctx.occurredAt(),
                    sequence,
                    0);
            changes.vesselCall(calls.insert(created), ctx.occurredAt());
            return;
        }
        if (stale.isStale(current.provenance(), current.lastSourceSequence(), current.lastOccurredAt(), ctx)) {
            stale.open(
                    ctx,
                    StateChanges.VESSEL_CALL,
                    current.id().toString(),
                    current.provenance(),
                    current.lastSourceSequence(),
                    current.lastOccurredAt());
            return;
        }
        VesselCall merged = new VesselCall(
                current.id(),
                current.portId(),
                current.callCode(),
                or(text(p, "vesselName"), current.vesselName()),
                or(text(p, "imo"), current.imo()),
                or(decimal(p, "loaM"), current.loaM()),
                or(decimal(p, "beamM"), current.beamM()),
                or(status, current.status()),
                or(eta, current.eta()),
                or(ata, current.ata()),
                or(etd, current.etd()),
                or(atd, current.atd()),
                or(berth, current.berthAssetId()),
                provenance,
                ctx.occurredAt(),
                sequence,
                current.version());
        changes.vesselCall(calls.update(merged), ctx.occurredAt());
    }

    private static VesselStatus implied(Instant ata, Instant atd) {
        return atd != null ? VesselStatus.DEPARTED : ata != null ? VesselStatus.ALONGSIDE : VesselStatus.EXPECTED;
    }

    private static <T> T or(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private static String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static VesselStatus status(JsonNode payload) {
        String value = text(payload, "status");
        if (value == null) {
            return null;
        }
        try {
            return VesselStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new RejectedEventException(DeadLetterReason.INVALID_EVENT, "'" + value + "' is not a vessel status");
        }
    }

    /** One decimal like the column, so the change body shows what a later read of the row shows. */
    private static BigDecimal decimal(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return node == null || !node.isNumber() ? null : node.decimalValue().setScale(1, RoundingMode.HALF_UP);
    }

    private static Instant instant(JsonNode payload, String field) {
        String value = text(payload, field);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant().truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException e) {
            throw new RejectedEventException(
                    DeadLetterReason.INVALID_EVENT, "'" + value + "' is not a date-time for " + field);
        }
    }
}
