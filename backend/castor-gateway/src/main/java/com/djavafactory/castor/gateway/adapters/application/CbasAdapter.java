package com.djavafactory.castor.gateway.adapters.application;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.EventType;
import com.djavafactory.castor.gateway.adapters.application.AdapterSupport.Draft;
import com.djavafactory.castor.gateway.adapters.domain.SourceFields;
import com.djavafactory.castor.gateway.normalize.domain.InboxRecord;
import com.djavafactory.castor.gateway.normalize.domain.Rejection;
import com.djavafactory.castor.gateway.normalize.domain.SourceAdapter;

/**
 * Source cbas (the berth allocation system): a vessel call and berth message becomes {@code vessel.call.updated}. The
 * {@code callRef} is the vessel call id as it is; the {@code berthRef} is mapped to the berth asset valid when the
 * message happened. A CBAS message is the complete picture of a call, so the vessel, status and ETA are required here
 * (the contract itself only asks for the call code, for the sake of EDI milestones). It has no timestamp of its own: it
 * happened at the departure or arrival it reports, else when it was received.
 */
@ApplicationScoped
public class CbasAdapter implements SourceAdapter {

    private final AdapterSupport support;

    public CbasAdapter(AdapterSupport support) {
        this.support = support;
    }

    @Override
    public String source() {
        return "cbas";
    }

    @Override
    public List<CanonicalEvent> normalize(InboxRecord record) throws Rejection {
        JsonNode raw = SourceFields.object(record.raw());
        String callRef = SourceFields.required(raw, "callRef");
        JsonNode vessel = SourceFields.object(raw, "vessel");
        Instant eta = SourceFields.requiredInstant(raw, "eta");
        Instant ata = SourceFields.optionalInstant(raw, "ata");
        Instant etd = SourceFields.optionalInstant(raw, "etd");
        Instant atd = SourceFields.optionalInstant(raw, "atd");
        Instant occurredAt = atd != null ? atd : ata != null ? ata : record.receivedAt();

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("callCode", callRef);
        payload.put("vesselName", SourceFields.required(vessel, "name"));
        String imo = SourceFields.optional(vessel, "imo");
        if (imo != null) {
            payload.put("imo", imo);
        }
        payload.put("loaM", SourceFields.requiredNumber(vessel, "loa"));
        payload.put("beamM", SourceFields.requiredNumber(vessel, "beam"));
        payload.put("status", SourceFields.required(raw, "status").toUpperCase(Locale.ROOT));
        payload.put("eta", eta.toString());
        put(payload, "ata", ata);
        put(payload, "etd", etd);
        put(payload, "atd", atd);
        String berthRef = SourceFields.optional(raw, "berthRef");
        if (berthRef != null) {
            payload.put("berthId", support.identity(record, "BERTH", berthRef, occurredAt));
        }

        Long sequence = raw.hasNonNull("seq") && raw.get("seq").canConvertToLong()
                ? raw.get("seq").asLong()
                : null;
        return List.of(support.event(
                record,
                new Draft(
                        EventType.VESSEL_CALL_UPDATED.code(),
                        "VESSEL_CALL",
                        callRef,
                        callRef,
                        occurredAt,
                        sequence,
                        payload)));
    }

    private static void put(ObjectNode payload, String name, Instant value) {
        if (value != null) {
            payload.put(name, value.toString());
        }
    }
}
