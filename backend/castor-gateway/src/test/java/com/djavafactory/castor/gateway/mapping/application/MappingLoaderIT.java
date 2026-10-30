package com.djavafactory.castor.gateway.mapping.application;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.gateway.mapping.domain.MappingSeed;
import com.djavafactory.castor.gateway.mapping.infrastructure.MappingSeedStep;
import com.djavafactory.castor.gateway.support.Db;
import com.djavafactory.castor.gateway.support.MappingSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mapping loader on the real database. Castor-core's {@code layout.published_asset} is a view there; here it is a
 * table of the same columns that the test fills, because the gateway only ever reads it.
 */
@QuarkusTest
class MappingLoaderIT extends MappingSupport {

    private static final String MHC1 = "3f0c5a52-1c1b-4b57-9a0e-2f2d3a6c9b10";
    private static final String B1 = "9d7e1b3a-77a1-4c6e-8c4d-0b6f4a1e2c11";

    @Inject
    LoadMappings loader;

    @Inject
    MappingSeedStep seedStep;

    @BeforeEach
    void publishedLayout() {
        Db.execute(dataSource, "create schema if not exists layout");
        Db.execute(
                dataSource,
                "create table if not exists layout.published_asset (port_code text, asset_id uuid, code text, type_code text)");
        Db.execute(dataSource, "delete from layout.published_asset");
        Db.execute(
                dataSource,
                "insert into layout.published_asset values ('westports', ?::uuid, 'MHC1', 'MOBILE_HARBOUR_CRANE'), "
                        + "('westports', ?::uuid, 'B1', 'BERTH'), ('othertown', gen_random_uuid(), 'MHC1', 'MOBILE_HARBOUR_CRANE')",
                MHC1,
                B1);
    }

    @AfterEach
    void dropLayout() {
        Db.execute(dataSource, "drop schema layout cascade");
    }

    private static final List<MappingSeed> SEEDS = List.of(
            new MappingSeed("itap", "EQUIPMENT", "CRANE-1", "MHC1", null),
            new MappingSeed("cbas", "BERTH", "BERTH-1", "B1", null),
            new MappingSeed("edic", "VESSEL_CALL", "EDI-1", null, "MV-ONE-1"),
            new MappingSeed("itap", "EQUIPMENT", "GHOST", "NO-SUCH-CODE", null));

    private long mappingRows() {
        return Db.count(dataSource, "select count(*) from ingest.identity_mapping");
    }

    @Test
    void knownCodesAreInsertedOnceAndASecondStartInsertsNothing() {
        LoadMappings.Result first = loader.load("westports", SEEDS);

        assertEquals(3, first.inserted());
        assertEquals(1, first.skipped());
        assertEquals(0, first.existing());
        assertFalse(first.pending());
        assertEquals(3, mappingRows());
        assertEquals(
                MHC1,
                Db.value(
                        dataSource,
                        "select canonical_id from ingest.identity_mapping where source_system = 'itap' and source_id = 'CRANE-1'"));
        assertEquals(
                "MV-ONE-1",
                Db.value(dataSource, "select canonical_id from ingest.identity_mapping where source_id = 'EDI-1'"));

        LoadMappings.Result second = loader.load("westports", SEEDS);

        assertEquals(0, second.inserted());
        assertEquals(3, second.existing());
        assertEquals(1, second.skipped());
        assertEquals(3, mappingRows());
    }

    @Test
    void anUnknownCodeIsSkippedNotInserted() {
        loader.load("westports", SEEDS);

        assertEquals(0, Db.count(dataSource, "select count(*) from ingest.identity_mapping where source_id = 'GHOST'"));
    }

    @Test
    void aSeedOfAnotherPortUsesThatPortsAssetIds() {
        loader.load("othertown", List.of(new MappingSeed("itap", "EQUIPMENT", "CRANE-1", "MHC1", null)));

        assertEquals(
                "othertown",
                Db.value(dataSource, "select port_id from ingest.identity_mapping where source_id = 'CRANE-1'"));
        assertFalse(
                MHC1.equals(Db.value(dataSource, "select canonical_id from ingest.identity_mapping")),
                "the asset of that port, not the one of westports");
    }

    @Test
    void aMappingAnOperatorChangedIsNotPutBack() {
        loader.load("westports", SEEDS);
        Db.execute(
                dataSource,
                "update ingest.identity_mapping set effective_to = '2026-10-29T10:00:00Z' where source_id = 'CRANE-1'");
        mapAsset("itap", "EQUIPMENT", "CRANE-1", "asset-new", Instant.parse("2026-10-29T10:00:00Z"), null);

        LoadMappings.Result again = loader.load("westports", SEEDS);

        assertEquals(0, again.inserted());
        assertEquals(
                2, Db.count(dataSource, "select count(*) from ingest.identity_mapping where source_id = 'CRANE-1'"));
    }

    @Test
    void overlappingRangesOfTheSameSourceIdAreRejectedByTheDatabase() {
        loader.load("westports", SEEDS);

        var failure = assertThrows(
                IllegalStateException.class,
                () -> mapAsset("itap", "EQUIPMENT", "CRANE-1", "asset-x", Instant.parse("2026-10-29T10:00:00Z"), null));

        assertEquals("23P01", ((SQLException) failure.getCause()).getSQLState());
        assertEquals(
                0, Db.count(dataSource, "select count(*) from ingest.identity_mapping where canonical_id = 'asset-x'"));
        // the same source id of another entity type or another source is a different thing
        mapAsset("sensors", "EQUIPMENT", "CRANE-1", "asset-y");
        mapAsset("itap", "STORAGE", "CRANE-1", "asset-z");
    }

    @Test
    void touchingRangesAreNotOverlapping() {
        mapAsset("itap", "EQUIPMENT", "CRANE-2", "asset-old", Instant.EPOCH, Instant.parse("2026-10-29T10:00:00Z"));
        mapAsset("itap", "EQUIPMENT", "CRANE-2", "asset-new", Instant.parse("2026-10-29T10:00:00Z"), null);

        assertEquals(2, mappingRows());
    }

    @Test
    void theLoaderWaitsWhenCastorCoreHasNotCreatedTheViewYet() {
        Db.execute(dataSource, "drop table layout.published_asset");

        LoadMappings.Result result = loader.load("westports", SEEDS);

        assertTrue(result.pending());
        assertEquals(0, mappingRows());
    }

    @Test
    void theLoaderWaitsWhenThePortHasNoPublishedLayoutYet() {
        Db.execute(dataSource, "delete from layout.published_asset");

        assertTrue(loader.load("westports", SEEDS).pending());
        assertEquals(0, mappingRows());
    }

    @Test
    void aSeedWithBothOrNeitherOfAssetCodeAndCanonicalIdIsSkipped() {
        LoadMappings.Result result = loader.load(
                "westports",
                List.of(
                        new MappingSeed("itap", "EQUIPMENT", "A", "MHC1", "also-an-id"),
                        new MappingSeed("itap", "EQUIPMENT", "B", null, null),
                        new MappingSeed("itap", "EQUIPMENT", " ", "MHC1", null)));

        assertEquals(0, result.inserted());
        assertEquals(3, result.skipped());
    }

    @Test
    void theShippedDemoSeedIsLoadedByTheStartupStep() {
        seedStep.run();

        assertEquals(
                B1,
                Db.value(
                        dataSource,
                        "select canonical_id from ingest.identity_mapping where source_system = 'cbas' and source_id = 'B1'"));
        assertEquals(
                MHC1,
                Db.value(
                        dataSource,
                        "select canonical_id from ingest.identity_mapping where source_system = 'itap' and source_id = 'MHC1'"));
        assertEquals(
                "MV-AURORA-0412",
                Db.value(
                        dataSource,
                        "select canonical_id from ingest.identity_mapping where source_id = 'EDI-AURORA-0412'"));
        // MHC1 of itap and sensors, B1 of cbas and the three demo calls of edic; the other codes are not in this layout
        assertEquals(6, mappingRows());

        seedStep.run();

        assertEquals(6, mappingRows(), "a second start inserts nothing");
    }
}
