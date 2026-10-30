package com.djavafactory.castor.gateway.filedrop.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.gateway.filedrop.domain.Checksum;
import com.djavafactory.castor.gateway.shared.infrastructure.CastorConfig;
import com.djavafactory.castor.gateway.support.Db;
import com.djavafactory.castor.gateway.support.MappingSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file-drop channel on real folders, the database and the broker: a file is taken only with a matching checksum
 * file, its records get the same de-duplication as REST, and the file ends in archive/ or rejected/.
 */
@QuarkusTest
class FileDropIT extends MappingSupport {

    private static final String CSV = """
            truckVisit,gateRef,direction,lane,ts
            V-1,G1,IN,1,2026-10-29T08:00:00Z
            V-2,G1,IN,2,2026-10-29T08:01:00Z
            V-1,G1,OUT,1,2026-10-29T08:30:00Z
            """;
    private static final Set<String> CSV_EVENTS =
            Set.of("V-1:IN:2026-10-29T08:00:00Z", "V-2:IN:2026-10-29T08:01:00Z", "V-1:OUT:2026-10-29T08:30:00Z");

    @Inject
    ProcessDrops drops;

    @Inject
    CastorConfig castor;

    private Path drop;

    @BeforeEach
    void emptyDropFolderAndAssets() throws IOException {
        drop = Path.of(castor.drop().dir().orElseThrow());
        if (Files.exists(drop)) {
            try (Stream<Path> tree = Files.walk(drop)) {
                for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(drop);
        mapAsset("scss", "GATE", "G1", "asset-g1");
    }

    private Path dir(String source) {
        return drop.resolve(source);
    }

    /** Drops a file the way a well-behaved sender does: the content first, the checksum file last. */
    private void dropFile(String source, String name, String content) throws IOException {
        Files.createDirectories(dir(source));
        Files.writeString(dir(source).resolve(name), content, StandardCharsets.UTF_8);
        Files.writeString(
                dir(source).resolve(name + ".sha256"),
                Checksum.sha256(content.getBytes(StandardCharsets.UTF_8)) + "  " + name + "\n");
    }

    private List<String> names(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private Path today() {
        return dir("scss")
                .resolve("archive")
                .resolve(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString());
    }

    private long inboxRows(String source) {
        return Db.count(dataSource, "select count(*) from ingest.inbox where source = ?", source);
    }

    private void publish() {
        normalizer.runOnce();
        relay.runOnce();
    }

    @Test
    void theSameCsvDroppedTwicePublishesItsEventsOnce() throws IOException {
        dropFile("scss", "gate-2026-10-29.csv", CSV);
        ProcessDrops.Result first = drops.runOnce();
        publish();

        assertEquals(1, first.archived());
        assertEquals(3, first.records());
        assertEquals(0, first.duplicates());
        assertEquals(CSV_EVENTS, kafkaSourceEvents(CSV_EVENTS));
        assertEquals(3, kafkaCount());

        dropFile("scss", "gate-2026-10-29.csv", CSV);
        ProcessDrops.Result second = drops.runOnce();
        publish();

        assertEquals(1, second.archived(), "the second file is archived too");
        assertEquals(3, second.duplicates());
        assertEquals(3, inboxRows("scss"), "no new inbox rows");
        assertEquals(3, outboxRows());
        assertEquals(3, kafkaCount(), "no new events on Kafka");
    }

    private long kafkaCount() {
        return com.djavafactory.castor.gateway.support.KafkaProbe.forSourceEvents(bootstrapServers, CSV_EVENTS)
                .size();
    }

    @Test
    void aTakenFileMovesToTodaysArchiveWithItsChecksumAndLeavesTheFolderEmpty() throws IOException {
        dropFile("scss", "a.csv", CSV);

        drops.runOnce();

        assertEquals(List.of("archive"), names(dir("scss")));
        assertEquals(List.of("a.csv", "a.csv.sha256"), names(today()));
        assertEquals(CSV, Files.readString(today().resolve("a.csv")));
    }

    @Test
    void theArchiveFolderIsNamedByTheDayOfTheInjectedClock() throws IOException {
        clock.set(java.time.Instant.parse("2027-01-02T23:59:00Z"));
        dropFile("scss", "a.csv", CSV);

        drops.runOnce();

        assertEquals(
                List.of("a.csv", "a.csv.sha256"),
                names(dir("scss").resolve("archive").resolve("2027-01-02")));
    }

    @Test
    void aFileWithoutItsChecksumFileIsNotTakenYet() throws IOException {
        Files.createDirectories(dir("scss"));
        Files.writeString(dir("scss").resolve("half.csv"), CSV);

        ProcessDrops.Result result = drops.runOnce();

        assertEquals(0, result.archived() + result.rejected());
        assertEquals(0, inboxRows("scss"));
        assertEquals(List.of("half.csv"), names(dir("scss")));

        Files.writeString(
                dir("scss").resolve("half.csv.sha256"), Checksum.sha256(CSV.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, drops.runOnce().archived(), "taken once the checksum file is there");
        assertEquals(3, inboxRows("scss"));
    }

    @Test
    void aChecksumThatDoesNotMatchMovesTheFileToRejectedWithAReasonAndStoresNothing() throws IOException {
        dropFile("scss", "bad.csv", CSV);
        Files.writeString(dir("scss").resolve("bad.csv.sha256"), "0".repeat(64) + "  bad.csv\n");

        ProcessDrops.Result result = drops.runOnce();

        assertEquals(1, result.rejected());
        assertEquals(0, inboxRows("scss"));
        assertEquals(List.of("rejected"), names(dir("scss")));
        assertEquals(
                List.of("bad.csv", "bad.csv.reason", "bad.csv.sha256"),
                names(dir("scss").resolve("rejected")));
        String reason = Files.readString(dir("scss").resolve("rejected").resolve("bad.csv.reason"));
        assertTrue(reason.contains("SHA-256"), reason);
        assertTrue(reason.contains("0".repeat(64)), reason);
    }

    @Test
    void aRecordWithoutWhatTheInboxNeedsRejectsTheWholeFileAndStoresNone() throws IOException {
        dropFile("scss", "short.csv", CSV + "V-3,G1,IN,1,not-a-time\n");

        ProcessDrops.Result result = drops.runOnce();

        assertEquals(1, result.rejected());
        assertEquals(0, inboxRows("scss"), "not even the good rows");
        String reason = Files.readString(dir("scss").resolve("rejected").resolve("short.csv.reason"));
        assertTrue(reason.contains("Record 4"), reason);
        assertTrue(reason.contains("/ts"), reason);
    }

    @Test
    void aFileThatIsNotInTheFormatOfItsSourceIsRejectedWithTheReason() throws IOException {
        dropFile("scss", "ragged.csv", "truckVisit,gateRef\nV-1\n");
        dropFile("scss", "notes.pdf", "hello");
        dropFile("cbas", "broken.json", "{ not json");
        dropFile("itap", "empty.json", "[]");

        ProcessDrops.Result result = drops.runOnce();

        assertEquals(4, result.rejected());
        assertTrue(Files.readString(dir("scss").resolve("rejected").resolve("ragged.csv.reason"))
                .contains("Row 2"));
        assertTrue(Files.readString(dir("scss").resolve("rejected").resolve("notes.pdf.reason"))
                .contains("cannot be named"));
        assertTrue(Files.readString(dir("cbas").resolve("rejected").resolve("broken.json.reason"))
                .contains("not valid JSON"));
        assertTrue(Files.readString(dir("itap").resolve("rejected").resolve("empty.json.reason"))
                .contains("no records"));
    }

    @Test
    void aJsonFileOfAnotherSourceIsStoredAndNormalizedLikeRestRecords() throws IOException {
        mapAsset("sensors", "EQUIPMENT", "GPS-QC1", "asset-qc1");
        dropFile("sensors", "positions.json", """
                [{"sensorId":"GPS-QC1","kind":"GPS","values":{"lon":101.3,"lat":2.98,"accuracyM":2},"ts":"2026-10-29T08:00:00Z"},
                 {"sensorId":"GPS-QC1","kind":"GPS","values":{"lon":101.4,"lat":2.98,"accuracyM":2},"ts":"2026-10-29T08:00:05Z"}]""");

        assertEquals(1, drops.runOnce().archived());
        publish();

        assertEquals(2, outboxRows());
        assertEquals(
                Set.of("GPS-QC1:GPS:2026-10-29T08:00:00Z", "GPS-QC1:GPS:2026-10-29T08:00:05Z"),
                kafkaSourceEvents(Set.of("GPS-QC1:GPS:2026-10-29T08:00:00Z", "GPS-QC1:GPS:2026-10-29T08:00:05Z")));
    }

    @Test
    void anEdiFileWithSeveralMessagesIsOneInboxRowEach() throws IOException {
        mapAsset("edic", "VESSEL_CALL", "EDI-1", "MV-ONE-1");
        dropFile(
                "edic",
                "calls.edi",
                "MSG+E-1'CALL+EDI-1'EVT+ARRIVED'DTM+202610290620'\nMSG+E-2'CALL+EDI-1'EVT+DEPARTED'DTM+202610291400'\n");

        ProcessDrops.Result result = drops.runOnce();
        publish();

        assertEquals(2, result.records());
        assertEquals(2, inboxRows("edic"));
        assertEquals(2, outboxRows());
    }

    @Test
    void aRecordArrivingByRestAndThenInAFileIsOneRecord() throws IOException {
        postTo(
                        "scss",
                        "{\"truckVisit\":\"V-1\",\"gateRef\":\"G1\",\"direction\":\"IN\",\"lane\":\"1\","
                                + "\"ts\":\"2026-10-29T08:00:00Z\"}")
                .then()
                .statusCode(202);
        dropFile("scss", "gate.csv", CSV);

        ProcessDrops.Result result = drops.runOnce();

        assertEquals(1, result.duplicates(), "V-1 IN is the same message by either door");
        assertEquals(3, inboxRows("scss"));
    }

    @Test
    void aSourceThatIsSwitchedOffKeepsItsFilesWhereTheyAre() throws IOException {
        Db.execute(dataSource, "update ingest.integration_source set enabled = false where code = 'scss'");
        try {
            dropFile("scss", "gate.csv", CSV);

            ProcessDrops.Result result = drops.runOnce();

            assertEquals(0, result.archived() + result.rejected());
            assertEquals(List.of("gate.csv", "gate.csv.sha256"), names(dir("scss")));
        } finally {
            Db.execute(dataSource, "update ingest.integration_source set enabled = true where code = 'scss'");
        }
    }

    @Test
    void aFileOfTheSameNameDroppedAgainDoesNotOverwriteTheArchivedOne() throws IOException {
        dropFile("scss", "gate.csv", CSV);
        drops.runOnce();
        dropFile("scss", "gate.csv", CSV);
        drops.runOnce();

        assertEquals(List.of("gate.csv", "gate.csv.1", "gate.csv.1.sha256", "gate.csv.sha256"), names(today()));
        assertFalse(Files.exists(dir("scss").resolve("gate.csv")));
    }

    @Test
    void theChecksumFileMayHoldJustTheHashInAnyCase() throws IOException {
        Files.createDirectories(dir("scss"));
        Files.writeString(dir("scss").resolve("x.csv"), CSV);
        Files.writeString(
                dir("scss").resolve("x.csv.sha256"),
                Checksum.sha256(CSV.getBytes(StandardCharsets.UTF_8)).toUpperCase() + "\r\n");

        assertEquals(1, drops.runOnce().archived());
    }

    @Test
    void theEmptyDropFolderIsLeftAlone() {
        ProcessDrops.Result result = drops.runOnce();

        assertEquals(new ProcessDrops.Result(0, 0, 0, 0), result);
    }
}
