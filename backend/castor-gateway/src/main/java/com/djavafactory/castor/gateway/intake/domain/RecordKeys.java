package com.djavafactory.castor.gateway.intake.domain;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

import com.djavafactory.castor.gateway.formats.domain.EdiMessage;
import com.djavafactory.castor.gateway.intake.domain.IntakeException.Field;

/**
 * What a record must carry for the inbox to recognise a repeat, whatever the source's format: the id the source gave the
 * message. Source {@code canonical} sends {@code sourceEventId} and {@code occurredAt} (see {@link IncomingRecord}); the
 * system sources send their own fields:
 *
 * <ul>
 *   <li>cbas: {@code msgId}
 *   <li>itap: {@code txnId} (and {@code ts})
 *   <li>edic: the MSG segment of the text in {@code edi}
 *   <li>scss and sensors have no message id, so the id is made of what makes a message unique: scss
 *       {@code truckVisit:direction:ts}, sensors {@code sensorId:kind:ts}
 * </ul>
 *
 * Whatever else is wrong with a message is for its adapter to find, and ends in the quarantine with a reason.
 */
public final class RecordKeys {

    private RecordKeys() {}

    /** The problems of one record; an empty list means it can be stored. */
    public static List<Field> problems(Source source, JsonNode record) {
        if (source == Source.CANONICAL) {
            return IncomingRecord.problems(record);
        }
        if (record == null || !record.isObject()) {
            return List.of(new Field("NOT_AN_OBJECT", "", "A record must be a JSON object"));
        }
        List<Field> problems = new ArrayList<>();
        switch (source) {
            case CBAS -> text(record, "msgId", problems);
            case ITAP -> {
                text(record, "txnId", problems);
                dateTime(record, "ts", problems);
            }
            case SCSS -> {
                text(record, "truckVisit", problems);
                text(record, "direction", problems);
                dateTime(record, "ts", problems);
            }
            case EDIC -> {
                if (text(record, "edi", problems)
                        && EdiMessage.messageId(record.get("edi").asText()) == null) {
                    problems.add(new Field("REQUIRED", "/edi", "The message has no MSG segment with an id"));
                }
            }
            case SENSORS -> {
                text(record, "sensorId", problems);
                text(record, "kind", problems);
                dateTime(record, "ts", problems);
            }
            default -> throw new IllegalStateException("Unhandled source " + source);
        }
        if (problems.isEmpty() && sourceEventId(source, record).length() > IncomingRecord.MAX_SOURCE_EVENT_ID_LENGTH) {
            problems.add(new Field(
                    "TOO_LONG",
                    "",
                    "The id of the message must have at most " + IncomingRecord.MAX_SOURCE_EVENT_ID_LENGTH
                            + " characters"));
        }
        return problems;
    }

    /** The source's id of a record that passed {@link #problems}. */
    public static String sourceEventId(Source source, JsonNode record) {
        return switch (source) {
            case CANONICAL -> IncomingRecord.sourceEventId(record);
            case CBAS -> record.get("msgId").asText();
            case ITAP -> record.get("txnId").asText();
            case SCSS ->
                record.get("truckVisit").asText() + ":"
                        + record.get("direction").asText().toUpperCase(Locale.ROOT) + ":"
                        + record.get("ts").asText();
            case EDIC -> EdiMessage.messageId(record.get("edi").asText());
            case SENSORS ->
                record.get("sensorId").asText() + ":" + record.get("kind").asText() + ":"
                        + record.get("ts").asText();
        };
    }

    private static boolean text(JsonNode record, String name, List<Field> problems) {
        JsonNode node = record.get(name);
        String pointer = "/" + name;
        if (node == null || node.isNull() || (node.isTextual() && node.asText().isBlank())) {
            problems.add(new Field("REQUIRED", pointer, name + " is required"));
            return false;
        }
        if (!node.isTextual()) {
            problems.add(new Field("INVALID_TYPE", pointer, name + " must be a string"));
            return false;
        }
        return true;
    }

    private static void dateTime(JsonNode record, String name, List<Field> problems) {
        if (!text(record, name, problems)) {
            return;
        }
        try {
            OffsetDateTime.parse(record.get(name).asText());
        } catch (DateTimeParseException e) {
            problems.add(new Field(
                    "INVALID_FORMAT",
                    "/" + name,
                    name + " must be an ISO-8601 date-time, such as 2026-10-29T08:20:00Z"));
        }
    }
}
