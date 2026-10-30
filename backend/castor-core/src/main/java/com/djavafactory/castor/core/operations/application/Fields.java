package com.djavafactory.castor.core.operations.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.core.operations.domain.DeadLetterReason;
import com.djavafactory.castor.core.operations.domain.RejectedEventException;

/** Reads the fields of an event payload the handlers of cargo, storage and gate share. */
final class Fields {

    /** The scale of every quantity column. */
    static final int QUANTITY_SCALE = 3;

    private Fields() {}

    static String text(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    static String required(JsonNode payload, String field) {
        String value = text(payload, field);
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required");
        }
        return value;
    }

    /** A number cut to the scale of the columns, so what is stored equals what the change body shows. */
    static BigDecimal quantity(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        if (node == null || !node.isNumber()) {
            throw invalid(field + " must be a number");
        }
        return node.decimalValue().setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
    }

    static BigDecimal optionalDecimal(JsonNode payload, String field, int scale) {
        JsonNode node = payload.get(field);
        return node == null || !node.isNumber() ? null : node.decimalValue().setScale(scale, RoundingMode.HALF_UP);
    }

    static Unit unit(JsonNode payload) {
        String code = text(payload, "unit");
        try {
            return Unit.parse(code);
        } catch (IllegalArgumentException e) {
            throw invalid("'" + code + "' is not a unit");
        }
    }

    static RejectedEventException invalid(String message) {
        return new RejectedEventException(DeadLetterReason.INVALID_EVENT, message);
    }
}
