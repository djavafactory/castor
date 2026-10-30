package com.djavafactory.castor.gateway.intake.domain;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.gateway.intake.domain.IntakeException.Field;
import com.djavafactory.castor.gateway.shared.domain.Json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncomingRecordTest {

    private static List<Field> problems(String json) throws Exception {
        return IncomingRecord.problems(Json.parse(json));
    }

    @Test
    void aRecordWithBothFieldsIsFine() throws Exception {
        String json = "{\"sourceEventId\":\"EV-1\",\"occurredAt\":\"2026-10-29T08:20:00+08:00\",\"x\":1}";

        assertTrue(problems(json).isEmpty());
        assertEquals("EV-1", IncomingRecord.sourceEventId(Json.parse(json)));
    }

    @Test
    void missingFieldsAreReportedWithTheirPointer() throws Exception {
        List<Field> problems = problems("{}");

        assertEquals(
                List.of(
                        new Field("REQUIRED", "/sourceEventId", "sourceEventId is required"),
                        new Field("REQUIRED", "/occurredAt", "occurredAt is required")),
                problems);
    }

    @Test
    void nullAndBlankCountAsMissing() throws Exception {
        assertEquals(
                2,
                problems("{\"sourceEventId\":null,\"occurredAt\":\"  \"}").stream()
                        .filter(f -> f.code().equals("REQUIRED"))
                        .count());
        assertEquals(
                "/sourceEventId",
                problems("{\"sourceEventId\":\"\",\"occurredAt\":\"2026-10-29T08:20:00Z\"}")
                        .get(0)
                        .pointer());
    }

    @Test
    void wrongTypesAreReported() throws Exception {
        List<Field> problems = problems("{\"sourceEventId\":42,\"occurredAt\":true}");

        assertEquals(
                List.of("INVALID_TYPE", "INVALID_TYPE"),
                problems.stream().map(Field::code).toList());
    }

    @Test
    void anUnparseableTimeIsReported() throws Exception {
        for (String at : new String[] {"2026-10-29", "08:20:00", "not a time", "2026-10-29T08:20:00"}) {
            List<Field> problems = problems("{\"sourceEventId\":\"a\",\"occurredAt\":\"" + at + "\"}");
            assertEquals("INVALID_FORMAT", problems.get(0).code(), at);
            assertEquals("/occurredAt", problems.get(0).pointer());
        }
    }

    @Test
    void aTooLongSourceEventIdIsReported() throws Exception {
        String id = "x".repeat(IncomingRecord.MAX_SOURCE_EVENT_ID_LENGTH + 1);

        assertEquals(
                "TOO_LONG",
                problems("{\"sourceEventId\":\"" + id + "\",\"occurredAt\":\"2026-10-29T08:20:00Z\"}")
                        .get(0)
                        .code());
        assertTrue(problems("{\"sourceEventId\":\"" + "x".repeat(200) + "\",\"occurredAt\":\"2026-10-29T08:20:00Z\"}")
                .isEmpty());
    }

    @Test
    void onlyObjectsAreRecords() throws Exception {
        for (String json : new String[] {"[]", "\"text\"", "7", "null"}) {
            JsonNode node = Json.parse(json);
            assertEquals("NOT_AN_OBJECT", IncomingRecord.problems(node).get(0).code(), json);
        }
        assertEquals("NOT_AN_OBJECT", IncomingRecord.problems(null).get(0).code());
    }

    @Test
    void intakeExceptionsKnowTheirStatus() {
        assertEquals(401, IntakeException.Kind.UNAUTHORIZED.status());
        assertEquals(403, IntakeException.Kind.FORBIDDEN.status());
        assertEquals(413, IntakeException.Kind.TOO_LARGE.status());
        assertEquals(400, IntakeException.Kind.BAD_JSON.status());
        assertEquals(422, IntakeException.Kind.UNPROCESSABLE.status());
        var e = new IntakeException(
                IntakeException.Kind.UNPROCESSABLE, "bad", List.of(new Field("REQUIRED", "/a", "a is required")));
        assertEquals("validation", e.kind().slug());
        assertEquals("Invalid record", e.kind().title());
        assertEquals(1, e.fields().size());
        assertTrue(
                new IntakeException(IntakeException.Kind.BAD_JSON, "x").fields().isEmpty());
    }
}
