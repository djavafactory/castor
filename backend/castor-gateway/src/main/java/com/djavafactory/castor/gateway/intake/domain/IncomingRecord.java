package com.djavafactory.castor.gateway.intake.domain;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.gateway.intake.domain.IntakeException.Field;

/**
 * What every source must send, whatever its format: a {@code sourceEventId} (the source's own id, which makes a repeat
 * recognisable) and {@code occurredAt} (an ISO-8601 date-time with an offset). Everything else is the adapter's concern.
 */
public final class IncomingRecord {

    static final int MAX_SOURCE_EVENT_ID_LENGTH = 200;

    private IncomingRecord() {}

    /** The problems of one record; an empty list means it can be stored. */
    public static List<Field> problems(JsonNode record) {
        if (record == null || !record.isObject()) {
            return List.of(new Field("NOT_AN_OBJECT", "", "A record must be a JSON object"));
        }
        List<Field> problems = new ArrayList<>();
        JsonNode id = record.get("sourceEventId");
        if (id == null || id.isNull() || (id.isTextual() && id.asText().isBlank())) {
            problems.add(new Field("REQUIRED", "/sourceEventId", "sourceEventId is required"));
        } else if (!id.isTextual()) {
            problems.add(new Field("INVALID_TYPE", "/sourceEventId", "sourceEventId must be a string"));
        } else if (id.asText().length() > MAX_SOURCE_EVENT_ID_LENGTH) {
            problems.add(new Field(
                    "TOO_LONG",
                    "/sourceEventId",
                    "sourceEventId must have at most " + MAX_SOURCE_EVENT_ID_LENGTH + " characters"));
        }
        JsonNode at = record.get("occurredAt");
        if (at == null || at.isNull() || (at.isTextual() && at.asText().isBlank())) {
            problems.add(new Field("REQUIRED", "/occurredAt", "occurredAt is required"));
        } else if (!at.isTextual()) {
            problems.add(new Field("INVALID_TYPE", "/occurredAt", "occurredAt must be a string"));
        } else if (!isDateTime(at.asText())) {
            problems.add(new Field(
                    "INVALID_FORMAT",
                    "/occurredAt",
                    "occurredAt must be an ISO-8601 date-time, such as 2026-10-29T08:20:00Z"));
        }
        return problems;
    }

    /** The source's id of a record that passed {@link #problems}. */
    public static String sourceEventId(JsonNode record) {
        return record.get("sourceEventId").asText();
    }

    private static boolean isDateTime(String text) {
        try {
            OffsetDateTime.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
