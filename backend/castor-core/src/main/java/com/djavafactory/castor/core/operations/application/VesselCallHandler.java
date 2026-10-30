package com.djavafactory.castor.core.operations.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Singleton;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.contracts.EventType;
import com.djavafactory.castor.core.operations.domain.DeadLetterReason;
import com.djavafactory.castor.core.operations.domain.EventOrder;
import com.djavafactory.castor.core.operations.domain.Provenance;
import com.djavafactory.castor.core.operations.domain.ReconciliationKind;
import com.djavafactory.castor.core.operations.domain.RejectedEventException;
import com.djavafactory.castor.core.operations.domain.SourcePrecedence;
import com.djavafactory.castor.core.operations.domain.VesselCall;
import com.djavafactory.castor.core.operations.domain.VesselCallRepository;
import com.djavafactory.castor.core.operations.domain.VesselCallValueRepository;
import com.djavafactory.castor.core.operations.domain.VesselCallValueRepository.Value;
import com.djavafactory.castor.core.operations.domain.VesselStatus;

/**
 * vessel.call.updated: creates or updates the call named by callCode. A field the event does not carry stays as it was
 * (an EDI message knows milestones only), and the berth must be a BERTH of the published layout. A call first seen
 * without a status gets the one its milestones imply.
 *
 * <p>Six fields have a system of record (ops.source_precedence): status, berth, eta, etd, ata, atd. Every value a
 * source reports is kept (ops.vessel_call_value); the call shows the value of the best-ranked source that has one, so a
 * lower source fills a field only while the higher one has none and never overrides it. When two sources disagree
 * (more than 15 minutes for a time, any difference for status and berth) a SOURCE_CONFLICT item opens, once while it
 * is open for the field. An event is stale only against the earlier events of its own source: sources are ranked by
 * the rule, not by who spoke last.
 */
@Singleton
public class VesselCallHandler implements EventHandler {

    /** Two sources whose times differ by no more than this agree. */
    static final Duration TOLERANCE = Duration.ofMinutes(15);

    private static final List<String> RULED = List.of("status", "berth", "eta", "ata", "etd", "atd");

    private final VesselCallRepository calls;
    private final VesselCallValueRepository values;
    private final SourcePrecedence precedence;
    private final PublishedLayout layout;
    private final StaleEvents stale;
    private final Reconciliations reconciliations;
    private final StateChanges changes;

    VesselCallHandler(
            VesselCallRepository calls,
            VesselCallValueRepository values,
            SourcePrecedence precedence,
            PublishedLayout layout,
            StaleEvents stale,
            Reconciliations reconciliations,
            StateChanges changes) {
        this.calls = calls;
        this.values = values;
        this.precedence = precedence;
        this.layout = layout;
        this.stale = stale;
        this.reconciliations = reconciliations;
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

        // what this event says about the ruled fields, as text
        Map<String, String> carried = new LinkedHashMap<>();
        put(carried, "status", status == null ? null : status.name());
        put(carried, "berth", berth == null ? null : berth.toString());
        put(carried, "eta", eta == null ? null : eta.toString());
        put(carried, "ata", ata == null ? null : ata.toString());
        put(carried, "etd", etd == null ? null : etd.toString());
        put(carried, "atd", atd == null ? null : atd.toString());

        if (current == null) {
            VesselCall created = calls.insert(new VesselCall(
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
                    0));
            record(ctx, created, carried);
            changes.vesselCall(created, ctx.occurredAt());
            return;
        }
        if (isStale(current, ctx)) {
            stale.open(
                    ctx,
                    StateChanges.VESSEL_CALL,
                    current.id().toString(),
                    current.provenance(),
                    current.lastSourceSequence(),
                    current.lastOccurredAt());
            return;
        }
        record(ctx, current, carried);
        Map<String, String> winners = new LinkedHashMap<>();
        boolean won = false;
        List<Value> latest = values.latestBySource(current.id());
        for (String field : carried.keySet()) {
            Value winner = winner(ctx, field, latest);
            winners.put(field, winner.value());
            won |= winner.source().equals(source(ctx));
            conflicts(ctx, current, field, carried.get(field), latest);
        }
        boolean otherFields = text(p, "vesselName") != null
                || text(p, "imo") != null
                || decimal(p, "loaM") != null
                || decimal(p, "beamM") != null;
        if (!carried.isEmpty() && !won && !otherFields) {
            // every ruled value lost to a better source: kept in the history, nothing to change in the call
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
                winners.containsKey("status") ? VesselStatus.valueOf(winners.get("status")) : current.status(),
                winners.containsKey("eta") ? Instant.parse(winners.get("eta")) : current.eta(),
                winners.containsKey("ata") ? Instant.parse(winners.get("ata")) : current.ata(),
                winners.containsKey("etd") ? Instant.parse(winners.get("etd")) : current.etd(),
                winners.containsKey("atd") ? Instant.parse(winners.get("atd")) : current.atd(),
                winners.containsKey("berth") ? UUID.fromString(winners.get("berth")) : current.berthAssetId(),
                provenance,
                ctx.occurredAt(),
                sequence,
                current.version());
        changes.vesselCall(calls.update(merged), ctx.occurredAt());
    }

    /** Older than an earlier event of the same source: of this call as a whole, or of that source's own history. */
    private boolean isStale(VesselCall current, ProjectionContext ctx) {
        String source = source(ctx);
        if (current.provenance().source().equalsIgnoreCase(source)
                && stale.isStale(current.provenance(), current.lastSourceSequence(), current.lastOccurredAt(), ctx)) {
            return true;
        }
        return values.lastFrom(current.id(), source)
                .filter(last -> EventOrder.isStale(
                        source,
                        last.sourceSequence(),
                        last.occurredAt(),
                        source,
                        ctx.event().sourceSequence(),
                        ctx.occurredAt()))
                .isPresent();
    }

    private void record(ProjectionContext ctx, VesselCall call, Map<String, String> carried) {
        carried.forEach((field, value) -> values.append(
                ctx.portId(),
                call.id(),
                ctx.eventId(),
                new Value(
                        field, source(ctx), value, ctx.occurredAt(), ctx.event().sourceSequence())));
    }

    /** The value of the best-ranked source that reported the field; ties of rank go to the latest report. */
    private Value winner(ProjectionContext ctx, String field, List<Value> latest) {
        List<String> order = order(ctx, field);
        Value best = null;
        for (Value v : latest) {
            if (!v.field().equals(field)) {
                continue;
            }
            if (best == null
                    || SourcePrecedence.rank(order, v.source()) < SourcePrecedence.rank(order, best.source())
                    || (SourcePrecedence.rank(order, v.source()) == SourcePrecedence.rank(order, best.source())
                            && v.occurredAt().isAfter(best.occurredAt()))) {
                best = v;
            }
        }
        return best;
    }

    /** One item per field while open: this event's value against what every other source reported. */
    private void conflicts(ProjectionContext ctx, VesselCall call, String field, String mine, List<Value> latest) {
        List<String> order = order(ctx, field);
        for (Value other : latest) {
            if (!other.field().equals(field)
                    || other.source().equals(source(ctx))
                    || !differ(field, mine, other.value())) {
                continue;
            }
            Value winner = winner(ctx, field, latest);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("callCode", call.callCode());
            detail.put("applied", Map.of("source", winner.source(), "value", winner.value()));
            detail.put(
                    "other",
                    Map.of(
                            "source",
                            winner.source().equals(source(ctx)) ? other.source() : source(ctx),
                            "value",
                            winner.source().equals(source(ctx)) ? other.value() : mine));
            detail.put("precedence", order);
            if (isTime(field)) {
                detail.put(
                        "differenceMinutes",
                        Duration.between(Instant.parse(mine), Instant.parse(other.value()))
                                .abs()
                                .toMinutes());
            }
            reconciliations.openOnce(
                    ctx,
                    ReconciliationKind.SOURCE_CONFLICT,
                    StateChanges.VESSEL_CALL,
                    call.id().toString(),
                    field,
                    detail);
            return;
        }
    }

    private List<String> order(ProjectionContext ctx, String field) {
        return precedence
                .sources(ctx.portId(), SourcePrecedence.VESSEL_CALL, field)
                .orElse(List.of());
    }

    private static boolean differ(String field, String a, String b) {
        if (!isTime(field)) {
            return !a.equals(b);
        }
        return Duration.between(Instant.parse(a), Instant.parse(b)).abs().compareTo(TOLERANCE) > 0;
    }

    private static boolean isTime(String field) {
        return RULED.indexOf(field) >= 2;
    }

    private static String source(ProjectionContext ctx) {
        return ctx.event().sourceSystem().toLowerCase();
    }

    private static void put(Map<String, String> into, String field, String value) {
        if (value != null) {
            into.put(field, value);
        }
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
