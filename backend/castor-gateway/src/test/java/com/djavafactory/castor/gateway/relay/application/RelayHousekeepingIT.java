package com.djavafactory.castor.gateway.relay.application;

import java.time.Duration;
import java.util.Map;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.gateway.housekeeping.application.RunHousekeeping;
import com.djavafactory.castor.gateway.housekeeping.domain.RetentionStore;
import com.djavafactory.castor.gateway.shared.infrastructure.CastorConfig;
import com.djavafactory.castor.gateway.support.Db;
import com.djavafactory.castor.gateway.support.Events;
import com.djavafactory.castor.gateway.support.PipelineSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Housekeeping with the stepped clock. Published outbox rows and the raw payload of normalized records go after 7 days,
 * quarantine entries after 30; what has not been published is never deleted, and an inbox row is never deleted at all,
 * because its key is what recognises a repeat.
 */
@QuarkusTest
class RelayHousekeepingIT extends PipelineSupport {

    @Inject
    RunHousekeeping housekeeping;

    @Inject
    RetentionStore store;

    private long count(String sql) {
        return Db.count(dataSource, sql);
    }

    @Test
    void retentionFollowsTheClock() {
        // published: its outbox row and raw payload are due after 7 days
        String published = Events.newSourceEventId();
        accept(published);
        normalizer.runOnce();
        relay.runOnce();
        // normalized but still unpublished (its outbox row must stay however old it gets)
        String waiting = Events.newSourceEventId();
        accept(waiting);
        normalizer.runOnce();
        // quarantined: kept 30 days
        String bad = Events.newSourceEventId();
        String badReception =
                post(Events.invalidStatus(bad)).then().statusCode(202).extract().path("receptionId");
        normalizer.runOnce();
        assertEquals(2, count("select count(*) from ingest.outbox"));
        assertEquals(3, count("select count(*) from ingest.inbox where raw is not null"));

        clock.advance(Duration.ofDays(7).minusSeconds(1));
        assertEquals(new RetentionStore.Purged(0, 0, 0), housekeeping.runOnce(), "6 days 23:59:59 is too early");
        assertEquals(2, count("select count(*) from ingest.outbox"));

        clock.advance(Duration.ofSeconds(2));
        assertEquals(new RetentionStore.Purged(1, 2, 0), housekeeping.runOnce(), "after 7 days");
        assertEquals(1, count("select count(*) from ingest.outbox"), "only the unpublished row is left");
        assertEquals(
                0,
                count("select count(*) from ingest.outbox where published_at is not null"),
                "the published one is gone");
        assertEquals(
                1,
                count("select count(*) from ingest.inbox where raw is not null"),
                "raw payloads of the normalized records are gone, the quarantined record keeps its payload");
        assertNotNull(Db.value(dataSource, "select raw from ingest.inbox where reception_id = ?::uuid", badReception));
        assertEquals(3, count("select count(*) from ingest.inbox"), "inbox rows are never deleted");
        assertEquals(1, count("select count(*) from ingest.quarantine"));

        // A repeat of a record whose payload is long gone is still recognised
        post(Events.status(published)).then().statusCode(200);

        assertEquals(new RetentionStore.Purged(0, 0, 0), housekeeping.runOnce(), "nothing more is due");

        clock.advance(Duration.ofDays(23).minusSeconds(2));
        assertEquals(new RetentionStore.Purged(0, 0, 0), housekeeping.runOnce(), "29 days 23:59:59 is too early");

        clock.advance(Duration.ofSeconds(2));
        assertEquals(new RetentionStore.Purged(0, 1, 1), housekeeping.runOnce(), "after 30 days");
        assertEquals(0, count("select count(*) from ingest.quarantine"));
        assertNull(Db.value(dataSource, "select raw from ingest.inbox where reception_id = ?::uuid", badReception));
        assertEquals("QUARANTINED", inboxStatus(badReception));
        assertEquals(3, count("select count(*) from ingest.inbox"));

        // The event that was waiting is still published, however late
        assertEquals(1, relay.runOnce());
        assertEquals(0, count("select count(*) from ingest.outbox where published_at is null"));
    }

    @Test
    void theRetentionTimesAreConfigurable() {
        var castor = new SmallRyeConfigBuilder()
                .withMapping(CastorConfig.class)
                .withDefaultValues(Map.of(
                        "castor.env", "test",
                        "castor.port-code", "westports",
                        "castor.retention.outbox", "PT1H",
                        "castor.retention.raw", "PT2H",
                        "castor.retention.quarantine", "PT3H"))
                .build()
                .getConfigMapping(CastorConfig.class);
        var hourly = new RunHousekeeping(store, clock, castor);
        accept(Events.newSourceEventId());
        normalizer.runOnce();
        relay.runOnce();
        String bad = post(Events.invalidStatus(Events.newSourceEventId()))
                .then()
                .statusCode(202)
                .extract()
                .path("receptionId");
        normalizer.runOnce();

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        assertEquals(new RetentionStore.Purged(1, 0, 0), hourly.runOnce());
        clock.advance(Duration.ofHours(1));
        assertEquals(new RetentionStore.Purged(0, 1, 0), hourly.runOnce());
        clock.advance(Duration.ofHours(1));
        assertEquals(new RetentionStore.Purged(0, 1, 1), hourly.runOnce());
        assertEquals("QUARANTINED", inboxStatus(bad));
    }
}
