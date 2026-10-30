package com.djavafactory.castor.core.operations.application;

import java.time.Instant;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.djavafactory.castor.core.operations.domain.CargoLot;
import com.djavafactory.castor.core.operations.domain.EquipmentState;
import com.djavafactory.castor.core.operations.domain.GateVisit;
import com.djavafactory.castor.core.operations.domain.StorageInventory;
import com.djavafactory.castor.core.operations.domain.VesselCall;

/**
 * Records a projection's change with the public DTO as its body, so what the stream and Kafka carry is exactly one
 * item of the snapshot.
 */
@ApplicationScoped
public class StateChanges {

    /** The entity types of the stream contract (STATE-02); the browser client accepts these and no other spelling. */
    public static final String EQUIPMENT = "equipment";

    public static final String VESSEL_CALL = "vessel-call";

    public static final String INVENTORY = "inventory";

    public static final String CARGO_LOT = "cargo-lot";

    public static final String GATE_VISIT = "gate-visit";

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final StateChangeRecorder recorder;
    private final StateViews views;
    private final ObjectMapper json;

    StateChanges(StateChangeRecorder recorder, StateViews views, ObjectMapper json) {
        this.recorder = recorder;
        this.views = views;
        this.json = json;
    }

    public void equipment(EquipmentState state, Instant occurredAt) {
        recorder.record(
                state.portId(),
                EQUIPMENT,
                state.assetId().toString(),
                state.version(),
                occurredAt,
                json.convertValue(views.equipment(state), OBJECT));
    }

    public void vesselCall(VesselCall call, Instant occurredAt) {
        recorder.record(
                call.portId(),
                VESSEL_CALL,
                call.id().toString(),
                call.version(),
                occurredAt,
                json.convertValue(views.vesselCall(call), OBJECT));
    }

    public void inventory(StorageInventory inventory, Instant occurredAt) {
        recorder.record(
                inventory.portId(),
                INVENTORY,
                inventory.storageAssetId() + ":" + inventory.commodity(),
                inventory.version(),
                occurredAt,
                json.convertValue(views.inventory(inventory), OBJECT));
    }

    public void cargoLot(CargoLot lot, Instant occurredAt) {
        recorder.record(
                lot.portId(),
                CARGO_LOT,
                lot.id().toString(),
                lot.version(),
                occurredAt,
                json.convertValue(views.cargoLot(lot), OBJECT));
    }

    public void gateVisit(GateVisit visit, Instant occurredAt) {
        recorder.record(
                visit.portId(),
                GATE_VISIT,
                visit.truckVisitId() + ":" + visit.gateAssetId(),
                visit.version(),
                occurredAt,
                json.convertValue(views.gateVisit(visit), OBJECT));
    }
}
