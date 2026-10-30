package com.djavafactory.castor.core.operations.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

import jakarta.inject.Singleton;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.contracts.EventType;
import com.djavafactory.castor.core.operations.domain.EquipmentRepository;
import com.djavafactory.castor.core.operations.domain.EquipmentState;
import com.djavafactory.castor.core.operations.domain.Position;

/**
 * equipment.position.observed: position, heading, accuracy and the time of the fix. A position outside the port extent
 * is a dead letter (OUT_OF_EXTENT), never stored.
 */
@Singleton
public class EquipmentPositionHandler extends EquipmentHandler {

    private static final BigDecimal FULL_TURN = BigDecimal.valueOf(360);

    EquipmentPositionHandler(
            EquipmentRepository equipment, PublishedLayout layout, StaleEvents stale, StateChanges changes) {
        super(equipment, layout, stale, changes);
    }

    @Override
    public EventType type() {
        return EventType.EQUIPMENT_POSITION_OBSERVED;
    }

    @Override
    void check(ProjectionContext ctx, PublishedLayout layout) {
        JsonNode payload = ctx.event().payload();
        layout.requireInExtent(
                ctx.portId(),
                number(payload, "lon").doubleValue(),
                number(payload, "lat").doubleValue());
    }

    @Override
    EquipmentState apply(ProjectionContext ctx, EquipmentState row) {
        JsonNode payload = ctx.event().payload();
        BigDecimal heading = payload.hasNonNull("headingDeg") ? number(payload, "headingDeg") : null;
        BigDecimal accuracy = number(payload, "accuracyM");
        return row.withPosition(new Position(
                number(payload, "lon").doubleValue(),
                number(payload, "lat").doubleValue(),
                heading == null ? null : heading(heading),
                // the columns keep two decimals: the change body shows what a later read of the row shows
                accuracy.setScale(2, RoundingMode.HALF_UP),
                ctx.occurredAt()));
    }

    /** Two decimals like the column; 359.996 becomes 0.00, not the 360.00 the column refuses. */
    private static BigDecimal heading(BigDecimal degrees) {
        BigDecimal rounded = degrees.setScale(2, RoundingMode.HALF_UP);
        return rounded.compareTo(FULL_TURN) >= 0 ? rounded.subtract(FULL_TURN) : rounded;
    }

    private static BigDecimal number(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || !node.isNumber()) {
            throw invalid("Position field " + field + " is missing or not a number");
        }
        return node.decimalValue();
    }
}
