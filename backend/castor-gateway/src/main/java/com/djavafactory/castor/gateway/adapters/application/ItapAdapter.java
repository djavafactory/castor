package com.djavafactory.castor.gateway.adapters.application;

import java.time.Instant;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.EventType;
import com.djavafactory.castor.gateway.adapters.application.AdapterSupport.Draft;
import com.djavafactory.castor.gateway.adapters.domain.SourceFields;
import com.djavafactory.castor.gateway.mapping.domain.Amounts;
import com.djavafactory.castor.gateway.mapping.domain.Amounts.Amount;
import com.djavafactory.castor.gateway.mapping.domain.MappingException;
import com.djavafactory.castor.gateway.normalize.domain.InboxRecord;
import com.djavafactory.castor.gateway.normalize.domain.Rejection;
import com.djavafactory.castor.gateway.normalize.domain.SourceAdapter;

/**
 * Source itap (the cargo and yard system): a cargo message becomes {@code cargo.lot.registered} when the lot has no
 * {@code fromLoc} (a new lot) or {@code cargo.lot.moved} when it goes from one location to another. A lot is created by
 * its source, so its id is {@code itap:<lotRef>} and needs no mapping; locations and the equipment that handled the move
 * are mapped. Family and commodity codes go through the cargo code mapping, and the quantity must be in a unit that fits
 * the family. The location of a new lot is not part of its registration, so {@code toLoc} is not read then.
 */
@ApplicationScoped
public class ItapAdapter implements SourceAdapter {

    private final AdapterSupport support;

    public ItapAdapter(AdapterSupport support) {
        this.support = support;
    }

    @Override
    public String source() {
        return "itap";
    }

    @Override
    public List<CanonicalEvent> normalize(InboxRecord record) throws Rejection {
        JsonNode raw = SourceFields.object(record.raw());
        String lotRef = SourceFields.required(raw, "lotRef");
        Instant at = SourceFields.requiredInstant(raw, "ts");
        String lotId = "itap:" + lotRef;
        String fromLoc = SourceFields.optional(raw, "fromLoc");

        String family = SourceFields.optional(raw, "family");
        String commodity = SourceFields.optional(raw, "commodity");
        String canonicalFamily = family == null ? null : support.cargoCode(record, "CARGO_FAMILY", family, at);
        String canonicalCommodity = commodity == null ? null : support.cargoCode(record, "COMMODITY", commodity, at);

        Amount amount;
        try {
            amount = Amounts.amount(raw.get("qty"), raw.get("uom"));
            if (canonicalFamily != null) {
                support.mappings().requireFit(canonicalFamily, amount.unit());
            }
        } catch (MappingException e) {
            throw AdapterSupport.rejection(e);
        }

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        String type;
        if (fromLoc == null) {
            type = EventType.CARGO_LOT_REGISTERED.code();
            payload.put("lotCode", lotRef);
            String consignment = SourceFields.optional(raw, "consignmentRef");
            if (consignment != null) {
                payload.put("consignmentCode", consignment);
            }
            if (canonicalFamily != null) {
                payload.put("cargoFamily", canonicalFamily);
            }
            if (canonicalCommodity != null) {
                payload.put("commodity", canonicalCommodity);
            }
            payload.put("unit", amount.unit().name());
            payload.put("quantity", amount.value());
        } else {
            type = EventType.CARGO_LOT_MOVED.code();
            String toLoc = SourceFields.required(raw, "toLoc");
            payload.put("lotId", lotId);
            payload.put("fromId", support.identity(record, "STORAGE", fromLoc, at));
            payload.put("toId", support.identity(record, "STORAGE", toLoc, at));
            payload.put("quantity", amount.value());
            payload.put("unit", amount.unit().name());
            String equipRef = SourceFields.optional(raw, "equipRef");
            if (equipRef != null) {
                payload.put("handledBy", support.identity(record, "EQUIPMENT", equipRef, at));
            }
        }
        return List.of(support.event(record, new Draft(type, "CARGO_LOT", lotId, lotId, at, null, payload)));
    }
}
