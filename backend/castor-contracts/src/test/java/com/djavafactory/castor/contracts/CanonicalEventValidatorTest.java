package com.djavafactory.castor.contracts;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalEventValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CanonicalEventValidator validator = new CanonicalEventValidator();

    @ParameterizedTest
    @EnumSource(EventType.class)
    void validExampleHasNoViolations(EventType type) {
        assertEquals(List.of(), validator.validate(Examples.valid(type)));
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void invalidExampleIsRejectedInItsPayload(EventType type) {
        List<Violation> violations = validator.validate(Examples.invalid(type));

        assertFalse(violations.isEmpty(), type + " invalid example passed");
        assertTrue(
                violations.stream().allMatch(v -> v.path().startsWith("/payload")),
                type + " violations should all be in the payload: " + violations);
    }

    @Test
    void reportsEveryViolationNotJustTheFirst() {
        List<Violation> violations = validator.validate(Examples.invalid(EventType.EQUIPMENT_POSITION_OBSERVED));

        List<String> paths = violations.stream().map(Violation::path).toList();
        assertTrue(paths.contains("/payload/lon"), paths.toString());
        assertTrue(paths.contains("/payload/headingDeg"), paths.toString());
        assertTrue(paths.contains("/payload/accuracyM"), paths.toString());
    }

    @Test
    void reportsEnvelopeAndPayloadProblemsTogether() throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree(Examples.invalid(EventType.GATE_TRANSACTION));
        event.remove("portId");
        event.put("quality", "GUESSED");

        List<String> paths =
                validator.validate(event).stream().map(Violation::path).toList();

        assertTrue(paths.contains("/quality"), paths.toString());
        assertTrue(paths.contains("/payload/direction"), paths.toString());
        assertTrue(paths.stream().anyMatch(p -> p.isEmpty() || p.equals("/portId")), paths.toString());
    }

    @Test
    void everyRequiredEnvelopeFieldIsChecked() throws Exception {
        for (String field : List.of(
                "eventId",
                "schemaVersion",
                "portId",
                "sourceSystem",
                "sourceEventId",
                "receptionId",
                "type",
                "entityType",
                "entityId",
                "aggregateId",
                "occurredAt",
                "receivedAt",
                "quality",
                "payload")) {
            ObjectNode event = (ObjectNode) JSON.readTree(Examples.valid(EventType.GATE_TRANSACTION));
            event.remove(field);

            assertFalse(validator.validate(event).isEmpty(), "missing " + field + " was accepted");
        }
    }

    @Test
    void optionalEnvelopeFieldsMayBeAbsent() throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree(Examples.valid(EventType.VESSEL_CALL_UPDATED));
        event.remove("sourceSequence");
        event.remove("correlationId");

        assertEquals(List.of(), validator.validate(event));
    }

    @Test
    void rejectsEnvelopeValuesOfTheWrongShape() throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree(Examples.valid(EventType.GATE_TRANSACTION));
        event.put("eventId", "not-a-uuid");
        event.put("schemaVersion", 2);
        event.put("occurredAt", "yesterday");
        event.put("aggregateId", "");
        event.put("sourceSequence", "7");

        List<String> paths =
                validator.validate(event).stream().map(Violation::path).toList();

        for (String expected :
                List.of("/eventId", "/schemaVersion", "/occurredAt", "/aggregateId", "/sourceSequence")) {
            assertTrue(paths.contains(expected), expected + " not reported: " + paths);
        }
    }

    @Test
    void rejectsAnUnknownEventType() throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree(Examples.valid(EventType.GATE_TRANSACTION));
        event.put("type", "gate.exploded");

        List<Violation> violations = validator.validate(event);

        assertEquals(1, violations.size());
        assertEquals("/type", violations.get(0).path());
    }

    @Test
    void acceptsAnAddedOptionalField() throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree(Examples.valid(EventType.GATE_TRANSACTION));
        event.put("addedLater", "v1 may gain optional fields");
        ((ObjectNode) event.get("payload")).put("kiosk", "K4");

        assertEquals(List.of(), validator.validate(event));
    }

    @Test
    void rejectsTextThatIsNotJson() {
        List<Violation> violations = validator.validate("{not json");

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).message().startsWith("Not valid JSON"));
    }

    @Test
    void rejectsJsonThatIsNotAnObject() {
        assertEquals(1, validator.validate("[1, 2]").size());
        assertEquals(1, validator.validate("null").size());
    }

    @Test
    void payloadMustBeAnObject() throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree(Examples.valid(EventType.GATE_TRANSACTION));
        event.put("payload", "text");

        assertTrue(validator.validate(event).stream().anyMatch(v -> v.path().equals("/payload")));
    }

    @Test
    void cargoFamilyIsDataNotAnEnum() throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree(Examples.valid(EventType.CARGO_LOT_REGISTERED));
        ((ObjectNode) event.get("payload")).put("cargoFamily", "CONTAINER");

        assertEquals(List.of(), validator.validate(event));
    }
}
