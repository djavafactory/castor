package com.djavafactory.castor.contracts;

import java.util.Optional;

/**
 * The event type catalogue of schema version 1. Each type has one JSON Schema, {@code schemas/v1/<code>.json}, for its
 * payload. A new type is a new constant plus a schema and an example pair (rule R6).
 */
public enum EventType {
    VESSEL_CALL_UPDATED("vessel.call.updated"),
    EQUIPMENT_STATUS_CHANGED("equipment.status.changed"),
    EQUIPMENT_POSITION_OBSERVED("equipment.position.observed"),
    EQUIPMENT_ASSIGNMENT_CHANGED("equipment.assignment.changed"),
    CARGO_LOT_REGISTERED("cargo.lot.registered"),
    CARGO_LOT_MOVED("cargo.lot.moved"),
    STORAGE_LEVEL_OBSERVED("storage.level.observed"),
    FLOW_RATE_OBSERVED("flow.rate.observed"),
    INVENTORY_ADJUSTED("inventory.adjusted"),
    GATE_TRANSACTION("gate.transaction"),
    TRUCK_APPROACH_OBSERVED("truck.approach.observed");

    private final String code;

    EventType(String code) {
        this.code = code;
    }

    /** The value of the envelope field {@code type}. */
    public String code() {
        return code;
    }

    /** Classpath location of the payload schema. */
    public String schemaResource() {
        return "/schemas/v1/" + code + ".json";
    }

    public static Optional<EventType> fromCode(String code) {
        for (EventType type : values()) {
            if (type.code.equals(code)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
