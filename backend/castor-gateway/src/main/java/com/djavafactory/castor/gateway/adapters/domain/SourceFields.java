package com.djavafactory.castor.gateway.adapters.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.gateway.normalize.domain.Rejection;

/**
 * Reads the fields of a source message. What is wrong with the source's own format is a {@code SCHEMA_INVALID}
 * rejection that names the field; the adapters never fill in a missing value.
 */
public final class SourceFields {

    private SourceFields() {}

    /** The record must be a JSON object. */
    public static JsonNode object(JsonNode record) throws Rejection {
        if (record == null || !record.isObject()) {
            throw invalid("A message must be a JSON object");
        }
        return record;
    }

    /** A child object that must be there. */
    public static JsonNode object(JsonNode parent, String name) throws Rejection {
        JsonNode node = parent.get(name);
        if (node == null || !node.isObject()) {
            throw invalid(name + " is required and must be an object");
        }
        return node;
    }

    /** Text that must be there and not blank; a number is read as its text (an IMO number, a lane). */
    public static String required(JsonNode parent, String name) throws Rejection {
        String value = optional(parent, name);
        if (value == null) {
            throw invalid(name + " is required");
        }
        return value;
    }

    /** Text or null when the field is missing, null or blank. */
    public static String optional(JsonNode parent, String name) throws Rejection {
        JsonNode node = parent.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() && !node.isNumber()) {
            throw invalid(name + " must be text");
        }
        String text = node.asText().strip();
        return text.isEmpty() ? null : text;
    }

    public static Instant requiredInstant(JsonNode parent, String name) throws Rejection {
        Instant value = optionalInstant(parent, name);
        if (value == null) {
            throw invalid(name + " is required");
        }
        return value;
    }

    /** An ISO-8601 date-time with an offset, or null when the field is missing. */
    public static Instant optionalInstant(JsonNode parent, String name) throws Rejection {
        String text = optional(parent, name);
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            throw invalid(name + " must be an ISO-8601 date-time with an offset, got '" + text + "'");
        }
    }

    /** A JSON number, exactly as sent. */
    public static BigDecimal requiredNumber(JsonNode parent, String name) throws Rejection {
        BigDecimal value = optionalNumber(parent, name);
        if (value == null) {
            throw invalid(name + " is required");
        }
        return value;
    }

    public static BigDecimal optionalNumber(JsonNode parent, String name) throws Rejection {
        JsonNode node = parent.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber()) {
            throw invalid(name + " must be a number");
        }
        return node.decimalValue();
    }

    private static Rejection invalid(String detail) {
        return new Rejection(Rejection.SCHEMA_INVALID, detail);
    }
}
