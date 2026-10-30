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
 * Source sensors (field instruments): {@code {sensorId, kind, values, ts}} becomes one event by kind. The sensor id is
 * mapped to the asset the sensor sits on: GPS and STATUS to equipment, CONVEYOR_RATE to the conveyor (an equipment
 * asset), TANK_LEVEL and SILO_LEVEL to storage, CAMERA_COUNT to a gate.
 *
 * <ul>
 *   <li>GPS {@code {lon, lat, headingDeg?, accuracyM}}: {@code equipment.position.observed}
 *   <li>STATUS {@code {status, reason?}}: {@code equipment.status.changed}
 *   <li>CONVEYOR_RATE {@code {rate, unit}} with a rate code such as {@code t/h}: {@code flow.rate.observed}
 *   <li>TANK_LEVEL, SILO_LEVEL {@code {commodity, quantity, uom, temperatureC?, densityKgM3?}}:
 *       {@code storage.level.observed}
 *   <li>CAMERA_COUNT {@code {count}}: {@code truck.approach.observed}, method CAMERA
 * </ul>
 */
@ApplicationScoped
public class SensorsAdapter implements SourceAdapter {

    private final AdapterSupport support;

    public SensorsAdapter(AdapterSupport support) {
        this.support = support;
    }

    @Override
    public String source() {
        return "sensors";
    }

    @Override
    public List<CanonicalEvent> normalize(InboxRecord record) throws Rejection {
        JsonNode raw = SourceFields.object(record.raw());
        String sensorId = SourceFields.required(raw, "sensorId");
        String kind = SourceFields.required(raw, "kind");
        Instant at = SourceFields.requiredInstant(raw, "ts");
        JsonNode values = SourceFields.object(raw, "values");
        Draft draft = switch (kind) {
            case "GPS" -> gps(record, sensorId, values, at);
            case "STATUS" -> status(record, sensorId, values, at);
            case "CONVEYOR_RATE" -> conveyorRate(record, sensorId, values, at);
            case "TANK_LEVEL", "SILO_LEVEL" -> level(record, sensorId, values, at);
            case "CAMERA_COUNT" -> cameraCount(record, sensorId, values, at);
            default ->
                throw new Rejection(
                        Rejection.SCHEMA_INVALID,
                        "kind must be GPS, STATUS, CONVEYOR_RATE, TANK_LEVEL, SILO_LEVEL or CAMERA_COUNT, got '" + kind
                                + "'");
        };
        return List.of(support.event(record, draft));
    }

    private Draft gps(InboxRecord record, String sensorId, JsonNode values, Instant at) throws Rejection {
        String equipment = support.identity(record, "EQUIPMENT", sensorId, at);
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("lon", SourceFields.requiredNumber(values, "lon"));
        payload.put("lat", SourceFields.requiredNumber(values, "lat"));
        if (SourceFields.optionalNumber(values, "headingDeg") != null) {
            payload.put("headingDeg", SourceFields.optionalNumber(values, "headingDeg"));
        }
        payload.put("accuracyM", SourceFields.requiredNumber(values, "accuracyM"));
        return draft(EventType.EQUIPMENT_POSITION_OBSERVED, "EQUIPMENT", equipment, at, payload);
    }

    private Draft status(InboxRecord record, String sensorId, JsonNode values, Instant at) throws Rejection {
        String equipment = support.identity(record, "EQUIPMENT", sensorId, at);
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("status", SourceFields.required(values, "status"));
        String reason = SourceFields.optional(values, "reason");
        if (reason != null) {
            payload.put("reason", reason);
        }
        return draft(EventType.EQUIPMENT_STATUS_CHANGED, "EQUIPMENT", equipment, at, payload);
    }

    private Draft conveyorRate(InboxRecord record, String sensorId, JsonNode values, Instant at) throws Rejection {
        String conveyor = support.identity(record, "EQUIPMENT", sensorId, at);
        Amount rate;
        try {
            rate = Amounts.rate(values.get("rate"), values.get("unit"));
        } catch (MappingException e) {
            throw AdapterSupport.rejection(e);
        }
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("assetId", conveyor);
        payload.put("rate", rate.value());
        payload.put("unit", rate.unit().name());
        return draft(EventType.FLOW_RATE_OBSERVED, "FLOW_ASSET", conveyor, at, payload);
    }

    private Draft level(InboxRecord record, String sensorId, JsonNode values, Instant at) throws Rejection {
        String storage = support.identity(record, "STORAGE", sensorId, at);
        String commodity = support.cargoCode(record, "COMMODITY", SourceFields.required(values, "commodity"), at);
        Amount amount;
        try {
            amount = Amounts.amount(values.get("quantity"), values.get("uom"));
        } catch (MappingException e) {
            throw AdapterSupport.rejection(e);
        }
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("storageId", storage);
        payload.put("commodity", commodity);
        payload.put("quantity", amount.value());
        payload.put("unit", amount.unit().name());
        if (SourceFields.optionalNumber(values, "temperatureC") != null) {
            payload.put("temperatureC", SourceFields.optionalNumber(values, "temperatureC"));
        }
        if (SourceFields.optionalNumber(values, "densityKgM3") != null) {
            payload.put("densityKgM3", SourceFields.optionalNumber(values, "densityKgM3"));
        }
        return draft(EventType.STORAGE_LEVEL_OBSERVED, "STORAGE", storage, at, payload);
    }

    private Draft cameraCount(InboxRecord record, String sensorId, JsonNode values, Instant at) throws Rejection {
        String gate = support.identity(record, "GATE", sensorId, at);
        JsonNode count = values.get("count");
        if (count == null || !count.isIntegralNumber()) {
            throw new Rejection(Rejection.SCHEMA_INVALID, "count is required and must be a whole number");
        }
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("gateId", gate);
        payload.put("count", count.asLong());
        payload.put("method", "CAMERA");
        return draft(EventType.TRUCK_APPROACH_OBSERVED, "GATE", gate, at, payload);
    }

    private static Draft draft(EventType type, String entityType, String entityId, Instant at, ObjectNode payload) {
        return new Draft(type.code(), entityType, entityId, entityId, at, null, payload);
    }
}
