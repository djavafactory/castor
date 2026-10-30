package com.djavafactory.castor.gateway.adapters.application;

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
import com.djavafactory.castor.gateway.formats.domain.EdiMessage;
import com.djavafactory.castor.gateway.formats.domain.FormatException;
import com.djavafactory.castor.gateway.normalize.domain.InboxRecord;
import com.djavafactory.castor.gateway.normalize.domain.Rejection;
import com.djavafactory.castor.gateway.normalize.domain.SourceAdapter;

/**
 * Source edic (EDI messages from agents and authorities): the text of {@code {"edi": "MSG+...'"}} becomes a
 * {@code vessel.call.updated} that carries the milestone and nothing else. {@code ARRIVED} is {@code ata},
 * {@code DEPARTED} {@code atd}, {@code ETA} {@code eta} and {@code ETD} {@code etd}. An EDI message is a declaration, not
 * an observation: it never sets the status or the berth of a call, so it can never become a physical movement. Its call
 * reference is the sender's, mapped to the vessel call id.
 */
@ApplicationScoped
public class EdicAdapter implements SourceAdapter {

    private static final java.util.Map<String, String> MILESTONES =
            java.util.Map.of("ARRIVED", "ata", "DEPARTED", "atd", "ETA", "eta", "ETD", "etd");

    private final AdapterSupport support;

    public EdicAdapter(AdapterSupport support) {
        this.support = support;
    }

    @Override
    public String source() {
        return "edic";
    }

    @Override
    public List<CanonicalEvent> normalize(InboxRecord record) throws Rejection {
        JsonNode raw = SourceFields.object(record.raw());
        EdiMessage message;
        try {
            message = EdiMessage.parse(SourceFields.required(raw, "edi"));
        } catch (FormatException e) {
            throw new Rejection(Rejection.SCHEMA_INVALID, e.getMessage());
        }
        String field = MILESTONES.get(message.event().toUpperCase(Locale.ROOT));
        if (field == null) {
            throw new Rejection(
                    Rejection.SCHEMA_INVALID,
                    "EVT " + message.event() + " is not a milestone Castor takes from EDI (" + MILESTONES.keySet()
                            + ")");
        }
        String callId = support.identity(record, "VESSEL_CALL", message.callRef(), message.at());

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("callCode", callId);
        payload.put(field, message.at().toString());
        return List.of(support.event(
                record,
                new Draft(
                        EventType.VESSEL_CALL_UPDATED.code(),
                        "VESSEL_CALL",
                        callId,
                        callId,
                        message.at(),
                        null,
                        payload)));
    }
}
