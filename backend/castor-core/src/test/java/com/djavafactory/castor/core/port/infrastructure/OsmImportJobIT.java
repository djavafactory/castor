package com.djavafactory.castor.core.port.infrastructure;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.port.domain.DataSourceCode;
import com.djavafactory.castor.core.shared.domain.RuleViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class OsmImportJobIT {

    private static final String SAMPLE = "fixtures/osm-sample.geojson";

    @Inject
    OsmImportJob job;

    @Inject
    DataSource dataSource;

    @BeforeEach
    @AfterEach
    void clean() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().executeUpdate("delete from ref.reference_feature");
            c.createStatement().executeUpdate("delete from ref.import_batch");
        }
    }

    private long count(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
                ResultSet rs = c.createStatement().executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void importsTheSampleAndStoresRowCountAndChecksum() throws SQLException {
        var result = job.run("westports", DataSourceCode.OSM, SAMPLE, false);

        assertEquals(4, result.rowCount());
        assertFalse(result.skipped());
        assertEquals(2, count("select count(*) from ref.reference_feature where kind = 'BUILDING'"));
        assertEquals(2, count("select count(*) from ref.reference_feature where kind = 'ROAD'"));
        assertEquals(
                4,
                count("select count(*) from ref.reference_feature where source_code = 'OSM' " + "and import_batch_id = "
                        + result.batchId()));
        assertEquals(
                1,
                count("select count(*) from ref.import_batch where id = " + result.batchId()
                        + " and kind = 'OSM' and source_code = 'OSM' and status = 'SUCCEEDED' and row_count = 4 "
                        + "and length(checksum) = 64 and finished_at is not null"));
    }

    @Test
    void importingTwiceGivesTheSameRows() throws SQLException {
        var first = job.run("westports", DataSourceCode.OSM, SAMPLE, false);
        var second = job.run("westports", DataSourceCode.OSM, SAMPLE, false);

        assertEquals(4, count("select count(*) from ref.reference_feature"));
        assertEquals(first.checksum(), second.checksum());
        assertEquals(2, count("select count(*) from ref.import_batch"));
        assertEquals(
                4,
                count("select count(*) from ref.reference_feature where import_batch_id = " + second.batchId()),
                "an upsert moves the rows to the newest batch");
    }

    @Test
    void anUnchangedFileIsSkippedWhenAsked() throws SQLException {
        var first = job.run("westports", DataSourceCode.OSM, SAMPLE, true);
        var second = job.run("westports", DataSourceCode.OSM, SAMPLE, true);

        assertFalse(first.skipped());
        assertTrue(second.skipped());
        assertEquals(first.batchId(), second.batchId());
        assertEquals(1, count("select count(*) from ref.import_batch"));
    }

    @Test
    void aSourceThatMayNotBePersistedIsRefused() throws SQLException {
        var e = assertThrows(
                RuleViolationException.class, () -> job.run("westports", DataSourceCode.GOOGLE, SAMPLE, false));

        assertEquals("SOURCE_NOT_PERSISTABLE", e.violations().get(0).code());
        assertEquals(0, count("select count(*) from ref.reference_feature"));
        assertEquals(0, count("select count(*) from ref.import_batch"), "no batch is opened for a refused source");
    }

    @Test
    void aBadFileMarksTheBatchFailedAndStoresNothing() throws Exception {
        byte[] sample = Files.readAllBytes(Path.of("src/test/resources/fixtures/osm-sample.geojson"));
        byte[] broken = new String(sample, StandardCharsets.UTF_8)
                .replace("\"BUILDING\"", "\"CASTLE\"")
                .getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> job.run("westports", DataSourceCode.OSM, broken, false));

        assertEquals(0, count("select count(*) from ref.reference_feature"));
        assertEquals(
                1, count("select count(*) from ref.import_batch where status = 'FAILED' and note like '%CASTLE%'"));
    }

    @Test
    void anUnknownPortIsRefused() {
        assertThrows(RuntimeException.class, () -> job.run("nowhere", DataSourceCode.OSM, SAMPLE, false));
    }
}
