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
 * Source scss (the gate system): a gate transaction, from a CSV row of a dropped file or a JSON record of the REST
 * endpoint, becomes {@code gate.transaction}. A truck visit is created by its source, so its id is
 * {@code scss:<truckVisit>}; the gate is mapped. The events of a gate share a Kafka key, the gate, so they stay in order.
 */
@ApplicationScoped
public class ScssAdapter implements SourceAdapter {

    private final AdapterSupport support;

    public ScssAdapter(AdapterSupport support) {
        this.support = support;
    }

    @Override
    public String source() {
        return "scss";
    }

    @Override
    public List<CanonicalEvent> normalize(InboxRecord record) throws Rejection {
        JsonNode raw = SourceFields.object(record.raw());
        String truckVisit = SourceFields.required(raw, "truckVisit");
        String gateRef = SourceFields.required(raw, "gateRef");
        Instant at = SourceFields.requiredInstant(raw, "ts");
        String visitId = "scss:" + truckVisit;
        String gateId = support.identity(record, "GATE", gateRef, at);

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("truckVisitId", visitId);
        payload.put("gateId", gateId);
        payload.put("direction", SourceFields.required(raw, "direction").toUpperCase(Locale.ROOT));
        String lane = SourceFields.optional(raw, "lane");
        if (lane != null) {
            payload.put("lane", lane);
        }
        return List.of(support.event(
                record,
                new Draft(EventType.GATE_TRANSACTION.code(), "TRUCK_VISIT", visitId, gateId, at, null, payload)));
    }
}
