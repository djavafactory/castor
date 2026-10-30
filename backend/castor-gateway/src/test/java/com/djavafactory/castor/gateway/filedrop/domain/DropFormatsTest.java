package com.djavafactory.castor.gateway.filedrop.domain;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.gateway.formats.domain.FormatException;
import com.djavafactory.castor.gateway.intake.domain.Source;
import com.djavafactory.castor.gateway.shared.domain.TtlCache;
import com.djavafactory.castor.gateway.support.TestClock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DropFormatsTest {

    private static List<JsonNode> records(Source source, String name, String content) throws FormatException {
        return DropFormats.records(source, name, content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void checksumIsTheHexSha256AndTheSidecarMayBeSha256sumOutput() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Checksum.sha256(new byte[0]));
        assertEquals("abc123", Checksum.expected("ABC123  file.csv\n"));
        assertEquals("abc123", Checksum.expected("  abc123\r\n"));
        assertEquals("", Checksum.expected(""));
    }

    @Test
    void jsonFilesHoldOneRecordOrAnArray() throws FormatException {
        assertEquals(1, records(Source.CBAS, "a.json", "{\"msgId\":\"1\"}").size());
        assertEquals(
                2,
                records(Source.ITAP, "A.JSON", "[{\"txnId\":\"1\"},{\"txnId\":\"2\"}]")
                        .size());
    }

    @Test
    void jsonLinesFilesHoldOneRecordPerLine() throws FormatException {
        List<JsonNode> records = records(Source.SENSORS, "a.jsonl", "{\"a\":1}\n\n{\"a\":2}\r\n");

        assertEquals(2, records.size());
        assertEquals(2, records.get(1).get("a").asInt());
    }

    @Test
    void csvIsForScssAndEdiTextForEdicOnly() throws FormatException {
        assertEquals(
                1, records(Source.SCSS, "a.csv", "truckVisit,gateRef\nV-1,G1\n").size());
        assertThrows(FormatException.class, () -> records(Source.CBAS, "a.csv", "a\n1\n"));
        List<JsonNode> edi = records(Source.EDIC, "a.edi", "MSG+1'CALL+A'\nMSG+2'CALL+B'\n");
        assertEquals(2, edi.size());
        assertEquals("MSG+2'CALL+B'", edi.get(1).get("edi").asText());
        assertEquals(1, records(Source.EDIC, "a.txt", "MSG+1'CALL+A'").size());
        assertThrows(FormatException.class, () -> records(Source.SCSS, "a.edi", "MSG+1'"));
    }

    @Test
    void aFileIsRefusedForWhatIsWrongWithItAsAWhole() {
        assertThrows(FormatException.class, () -> records(Source.CBAS, "a.json", ""));
        assertThrows(FormatException.class, () -> records(Source.CBAS, "a.json", "42"));
        assertThrows(FormatException.class, () -> records(Source.CBAS, "a.json", "{\"a\":"));
        assertThrows(FormatException.class, () -> records(Source.CBAS, "a.json", "[]"));
        assertThrows(FormatException.class, () -> records(Source.CBAS, "a.jsonl", "{\"a\":1}\nnot json\n"));
        assertThrows(FormatException.class, () -> records(Source.CBAS, "a.xml", "<a/>"));
        assertThrows(FormatException.class, () -> records(Source.EDIC, "a.edi", ""));
        assertThrows(
                FormatException.class,
                () -> DropFormats.records(Source.CBAS, "a.json", new byte[] {(byte) 0xC3, (byte) 0x28}));
    }

    @Test
    void ediTextWithoutAMsgSegmentIsOneRecordTheIntakeCheckRefuses() throws FormatException {
        List<JsonNode> records = records(Source.EDIC, "a.edi", "nothing to see");

        assertEquals(1, records.size());
        assertEquals(
                "REQUIRED /edi",
                com.djavafactory.castor.gateway.intake.domain.RecordKeys.problems(Source.EDIC, records.get(0)).stream()
                        .map(f -> f.code() + " " + f.pointer())
                        .findFirst()
                        .orElseThrow());
    }

    @Test
    void theCacheForgetsAnEntryWhenItsTimeIsUp() {
        TestClock clock = new TestClock();
        TtlCache<String, Integer> cache = new TtlCache<>(clock, Duration.ofSeconds(10), 2);
        int[] loads = {0};

        assertEquals(1, cache.get("a", k -> ++loads[0]));
        assertEquals(1, cache.get("a", k -> ++loads[0]));
        clock.set(Instant.parse("2026-10-29T08:00:10Z"));
        assertEquals(2, cache.get("a", k -> ++loads[0]));
        cache.get("b", k -> ++loads[0]);
        cache.get("c", k -> ++loads[0]);
        cache.clear();
        assertEquals(5, cache.get("a", k -> ++loads[0]));
    }
}
