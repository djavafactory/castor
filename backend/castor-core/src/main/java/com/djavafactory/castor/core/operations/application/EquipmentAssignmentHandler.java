package com.djavafactory.castor.core.operations.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Singleton;

import com.djavafactory.castor.contracts.EventType;
import com.djavafactory.castor.core.operations.domain.EquipmentRepository;
import com.djavafactory.castor.core.operations.domain.EquipmentState;

/**
 * equipment.assignment.changed: what the machine is assigned to. The event is the whole assignment, so a target the
 * event does not name is no longer assigned; no target at all means unassigned (null, not an empty object).
 */
@Singleton
public class EquipmentAssignmentHandler extends EquipmentHandler {

    private static final List<String> TARGETS = List.of("jobId", "vesselCallId", "flowNodeId");

    EquipmentAssignmentHandler(
            EquipmentRepository equipment, PublishedLayout layout, StaleEvents stale, StateChanges changes) {
        super(equipment, layout, stale, changes);
    }

    @Override
    public EventType type() {
        return EventType.EQUIPMENT_ASSIGNMENT_CHANGED;
    }

    @Override
    EquipmentState apply(ProjectionContext ctx, EquipmentState row) {
        Map<String, Object> assignment = new LinkedHashMap<>();
        for (String target : TARGETS) {
            String value = text(ctx.event().payload(), target);
            if (value != null) {
                assignment.put(target, value);
            }
        }
        return row.withAssignment(assignment.isEmpty() ? null : assignment);
    }
}
