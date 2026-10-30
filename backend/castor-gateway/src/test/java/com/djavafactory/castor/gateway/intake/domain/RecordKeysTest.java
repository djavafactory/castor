package com.djavafactory.castor.gateway.intake.domain;

import java.io.IOException;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.gateway.intake.domain.IntakeException.Field;
import com.djavafactory.castor.gateway.shared.domain.Json;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecordKeysTest {

    private static JsonNode json(String text) {
        try {
            return Json.parse(text);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static List<String> problems(Source source, String record) {
        return RecordKeys.problems(source, json(record)).stream()
                .map(f -> f.code() + " " + f.pointer())
                .toList();
    }

    @Test
    void canonicalKeepsItsSourceEventIdAndOccurredAt() {
        assertEquals(
                List.of(),
                problems(Source.CANONICAL, "{\"sourceEventId\":\"e1\",\"occurredAt\":\"2026-10-29T08:00:00Z\"}"));
        assertEquals(List.of("REQUIRED /sourceEventId", "REQUIRED /occurredAt"), problems(Source.CANONICAL, "{}"));
        assertEquals("e1", RecordKeys.sourceEventId(Source.CANONICAL, json("{\"sourceEventId\":\"e1\"}")));
    }

    @Test
    void cbasIsIdentifiedByItsMessageId() {
        assertEquals(List.of(), problems(Source.CBAS, "{\"msgId\":\"M-1\"}"));
        assertEquals(List.of("REQUIRED /msgId"), problems(Source.CBAS, "{\"callRef\":\"C\"}"));
        assertEquals(List.of("REQUIRED /msgId"), problems(Source.CBAS, "{\"msgId\":\" \"}"));
        assertEquals(List.of("INVALID_TYPE /msgId"), problems(Source.CBAS, "{\"msgId\":12}"));
        assertEquals("M-1", RecordKeys.sourceEventId(Source.CBAS, json("{\"msgId\":\"M-1\"}")));
    }

    @Test
    void itapIsIdentifiedByItsTransactionIdAndNeedsATimestamp() {
        assertEquals(List.of(), problems(Source.ITAP, "{\"txnId\":\"T-1\",\"ts\":\"2026-10-29T08:00:00Z\"}"));
        assertEquals(List.of("REQUIRED /txnId", "INVALID_FORMAT /ts"), problems(Source.ITAP, "{\"ts\":\"yesterday\"}"));
        assertEquals("T-1", RecordKeys.sourceEventId(Source.ITAP, json("{\"txnId\":\"T-1\"}")));
    }

    @Test
    void scssAndSensorsHaveNoMessageIdSoTheirIdIsWhatMakesAMessageUnique() {
        JsonNode gate = json("{\"truckVisit\":\"V-1\",\"direction\":\"in\",\"ts\":\"2026-10-29T08:00:00Z\"}");
        JsonNode sensor = json("{\"sensorId\":\"S-1\",\"kind\":\"GPS\",\"ts\":\"2026-10-29T08:00:00Z\"}");

        assertEquals(List.of(), RecordKeys.problems(Source.SCSS, gate));
        assertEquals(List.of(), RecordKeys.problems(Source.SENSORS, sensor));
        assertEquals("V-1:IN:2026-10-29T08:00:00Z", RecordKeys.sourceEventId(Source.SCSS, gate));
        assertEquals("S-1:GPS:2026-10-29T08:00:00Z", RecordKeys.sourceEventId(Source.SENSORS, sensor));
        assertEquals(
                List.of("REQUIRED /truckVisit", "REQUIRED /direction", "REQUIRED /ts"), problems(Source.SCSS, "{}"));
        assertEquals(List.of("REQUIRED /sensorId", "REQUIRED /kind", "REQUIRED /ts"), problems(Source.SENSORS, "{}"));
    }

    @Test
    void edicIsIdentifiedByTheMsgSegmentOfItsText() {
        assertEquals(List.of(), problems(Source.EDIC, "{\"edi\":\"MSG+E-1'CALL+C'\"}"));
        assertEquals(List.of("REQUIRED /edi"), problems(Source.EDIC, "{\"edi\":\"CALL+C'\"}"));
        assertEquals(List.of("REQUIRED /edi"), problems(Source.EDIC, "{}"));
        assertEquals("E-1", RecordKeys.sourceEventId(Source.EDIC, json("{\"edi\":\"MSG+E-1'CALL+C'\"}")));
    }

    @Test
    void aRecordThatIsNotAnObjectIsNotARecord() {
        for (Source source : Source.values()) {
            List<Field> problems = RecordKeys.problems(source, json("[1]"));
            assertEquals("NOT_AN_OBJECT", problems.get(0).code(), source.code());
        }
    }

    @Test
    void anIdThatIsTooLongIsRefused() {
        String longId = "x".repeat(201);

        assertEquals(List.of("TOO_LONG "), problems(Source.CBAS, "{\"msgId\":\"" + longId + "\"}"));
    }
}
