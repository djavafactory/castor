package com.djavafactory.castor.core.operations.application;

import java.util.List;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.core.support.ProjectorHarness;

import static com.djavafactory.castor.core.support.ProjectionTestSupport.count;
import static com.djavafactory.castor.core.support.ProjectionTestSupport.lines;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** The system of record of each vessel call field, from the seeded ops.source_precedence. */
@QuarkusTest
class PrecedenceIT {

    private static final String CALL = "MV-AURORA-0412";

    @Inject
    DataSource dataSource;

    @Inject
    CanonicalEventProcessor processor;

    private ProjectorHarness h;

    @BeforeEach
    void setUp() {
        h = new ProjectorHarness(dataSource, processor);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    private CanonicalEvent report(String source, String field, String value) {
        h.clock.step(60);
        CanonicalEvent event = h.events.callField(source, CALL, field, value);
        h.process(event);
        return event;
    }

    @Test
    void theSeededRulesNameCbasFirstForStatusBerthAndEstimatesAndEdicFirstForActuals() {
        assertEquals(
                List.of(
                        "ata edic,cbas",
                        "atd edic,cbas",
                        "berth cbas,edic",
                        "eta cbas,edic",
                        "etd cbas,edic",
                        "status cbas,edic"),
                lines(
                        dataSource,
                        "select field || ' ' || array_to_string(sources, ',') from ops.source_precedence "
                                + "where entity_type = 'VESSEL_CALL' order by field"));
    }

    @Test
    void cbasAndEdicDisagreeOnTheArrivalByThirtyMinutesEdicWinsAndASourceConflictOpens() {
        report("CBAS", "ata", "2026-10-30T08:20:00Z");
        CanonicalEvent edic = report("EDIC", "ata", "2026-10-30T08:50:00Z");

        assertEquals(
                List.of("2026-10-30 08:50:00"),
                lines(dataSource, "select (ata at time zone 'UTC')::text from ops.vessel_call"));
        assertEquals(
                List.of("SOURCE_CONFLICT vessel-call ata edic " + edic.eventId()),
                lines(
                        dataSource,
                        "select kind || ' ' || entity_type || ' ' || (detail ->> 'field') || ' ' "
                                + "|| (detail -> 'applied' ->> 'source') || ' ' || event_id from ops.reconciliation_item"));
        // both reports stay in the history, whichever was applied
        assertEquals(
                List.of("cbas 2026-10-30T08:20:00Z", "edic 2026-10-30T08:50:00Z"),
                lines(
                        dataSource,
                        "select source || ' ' || value from ops.vessel_call_value where field = 'ata' order by id"));
    }

    @Test
    void everyChangeOfTheCallIsAStateChangeAndAnIgnoredValueIsNot() {
        report("EDIC", "ata", "2026-10-30T08:50:00Z");
        report("CBAS", "ata", "2026-10-30T08:20:00Z");
        report("CBAS", "eta", "2026-10-30T07:00:00Z");

        assertEquals(
                List.of("vessel-call 0", "vessel-call 1"),
                lines(
                        dataSource,
                        "select entity_type || ' ' || entity_version from ops.state_change order by sequence"));
        assertEquals(1, count(dataSource, "select count(*) from ops.reconciliation_item"));
    }

    @Test
    void theLowerSourceFillsAFieldTheHigherOneDoesNotHaveAndNeverOpensAConflict() {
        report("CBAS", "ata", "2026-10-30T08:20:00Z");
        report("EDIC", "atd", "2026-10-31T01:00:00Z");

        assertEquals(
                List.of("2026-10-30 08:20:00 2026-10-31 01:00:00"),
                lines(
                        dataSource,
                        "select (ata at time zone 'UTC') || ' ' || (atd at time zone 'UTC') from ops.vessel_call"));
        assertEquals(0, count(dataSource, "select count(*) from ops.reconciliation_item"));
    }
}
