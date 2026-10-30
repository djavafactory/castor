package com.djavafactory.castor.gateway.formats.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FormatsTest {

    // --- EDI ---

    @Test
    void anEdiMessageIsReadSegmentBySegment() throws FormatException {
        EdiMessage message = EdiMessage.parse("MSG+E-0001'CALL+EDI-0412'EVT+ARRIVED'DTM+202610290620'");

        assertEquals(new EdiMessage("E-0001", "EDI-0412", "ARRIVED", Instant.parse("2026-10-29T06:20:00Z")), message);
    }

    @Test
    void theTimeMayHaveSecondsAndTheEdifactQualifiers() throws FormatException {
        assertEquals(
                Instant.parse("2026-10-29T06:20:45Z"),
                EdiMessage.parse("MSG+1'CALL+C'EVT+ETA'DTM+20261029062045'").at());
        assertEquals(
                Instant.parse("2026-10-29T06:20:00Z"),
                EdiMessage.parse("MSG+1'CALL+C'EVT+ETA'DTM+203:202610290620:203'")
                        .at());
        assertEquals(
                Instant.parse("2026-10-29T06:20:00Z"),
                EdiMessage.parse("MSG+1'CALL+C'EVT+ETA'DTM+202610290620Z'").at());
    }

    @Test
    void lineBreaksBetweenSegmentsAndUnknownSegmentsAreFine() throws FormatException {
        EdiMessage message = EdiMessage.parse("MSG+1'\nCALL+C'\nXYZ+whatever'\nEVT+ETD'\nDTM+202610290620'\n");

        assertEquals("C", message.callRef());
        assertEquals("ETD", message.event());
    }

    @Test
    void aMissingOrRepeatedOrImpossibleSegmentIsAnError() {
        assertThrows(FormatException.class, () -> EdiMessage.parse("MSG+1'CALL+C'EVT+ETA'"));
        assertThrows(FormatException.class, () -> EdiMessage.parse("CALL+C'EVT+ETA'DTM+202610290620'"));
        assertThrows(FormatException.class, () -> EdiMessage.parse("MSG+1'MSG+2'CALL+C'EVT+ETA'DTM+202610290620'"));
        assertThrows(FormatException.class, () -> EdiMessage.parse("MSG+1'CALL+'EVT+ETA'DTM+202610290620'"));
        assertThrows(FormatException.class, () -> EdiMessage.parse("MSG+1'CALL+C'EVT+ETA'DTM+yesterday'"));
        assertThrows(FormatException.class, () -> EdiMessage.parse("MSG+1'CALL+C'EVT+ETA'DTM+202613290620'"));
    }

    @Test
    void theMessageIdIsTheFirstMsgSegment() {
        assertEquals("E-1", EdiMessage.messageId("MSG+E-1'CALL+C'"));
        assertEquals("E-1", EdiMessage.messageId("CALL+C'MSG+E-1'"));
        assertNull(EdiMessage.messageId("CALL+C'"));
        assertNull(EdiMessage.messageId(null));
    }

    @Test
    void aFileIsSplitAtEveryMsgSegmentThatStartsALine() {
        List<String> messages = EdiMessage.split("MSG+1'CALL+A'\nEVT+ETA'DTM+202610290620'\n\nMSG+2'CALL+B'\n");

        assertEquals(2, messages.size());
        assertEquals("MSG+1'CALL+A'\nEVT+ETA'DTM+202610290620'", messages.get(0));
        assertEquals("MSG+2'CALL+B'", messages.get(1));
        assertEquals(List.of(), EdiMessage.split("  \n"));
        assertEquals(List.of(), EdiMessage.split(null));
    }

    // --- CSV ---

    @Test
    void aCsvHasOneRecordPerRowNamedByTheHeader() throws FormatException {
        List<ObjectNode> records = Csv.records("a,b\n1,2\r\n3,4");

        assertEquals(2, records.size());
        assertEquals("{\"a\":\"1\",\"b\":\"2\"}", records.get(0).toString());
        assertEquals("{\"a\":\"3\",\"b\":\"4\"}", records.get(1).toString());
    }

    @Test
    void quotedFieldsMayHoldCommasQuotesAndLineBreaks() throws FormatException {
        List<ObjectNode> records = Csv.records("name,note\n\"Smith, J\",\"said \"\"hi\"\"\nthen left\"\n");

        assertEquals("Smith, J", records.get(0).get("name").asText());
        assertEquals("said \"hi\"\nthen left", records.get(0).get("note").asText());
    }

    @Test
    void blankRowsAndTheByteOrderMarkAreIgnoredAndAnEmptyValueIsKept() throws FormatException {
        List<ObjectNode> records = Csv.records("﻿a,b\n\n1,\n   \n");

        assertEquals(1, records.size());
        assertEquals("", records.get(0).get("b").asText());
    }

    @Test
    void aHeaderOnlyCsvHasNoRecords() throws FormatException {
        assertEquals(List.of(), Csv.records("a,b\n"));
    }

    @Test
    void aBrokenCsvIsAnError() {
        assertThrows(FormatException.class, () -> Csv.records(""));
        assertThrows(FormatException.class, () -> Csv.records("a,a\n1,2"));
        assertThrows(FormatException.class, () -> Csv.records("a,\n1,2"));
        assertThrows(FormatException.class, () -> Csv.records("a,b\n1"));
        assertThrows(FormatException.class, () -> Csv.records("a,b\n1,2,3"));
        assertThrows(FormatException.class, () -> Csv.records("a,b\n\"1,2\n"));
    }
}
