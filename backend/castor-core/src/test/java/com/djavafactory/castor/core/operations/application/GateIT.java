package com.djavafactory.castor.core.operations.application;

import java.util.List;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.support.ProjectorHarness;

import static com.djavafactory.castor.core.support.ProjectionTestSupport.count;
import static com.djavafactory.castor.core.support.ProjectionTestSupport.lines;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Gate visits from the gate transactions, against the database and the snapshot. */
@QuarkusTest
@TestSecurity(user = "viewer", roles = "VIEWER")
class GateIT {

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

    private void tx(String truck, String direction) {
        h.clock.step(30);
        h.process(h.events.gate(truck, h.layout.gate(), direction, "L1"));
    }

    @Test
    void tenEntriesAndFourExitsLeaveSixOpenVisitsInTheSnapshot() {
        for (int i = 1; i <= 10; i++) {
            tx("TV-" + i, "IN");
        }
        for (int i = 1; i <= 4; i++) {
            tx("TV-" + i, "OUT");
        }

        assertEquals(10, count(dataSource, "select count(*) from ops.gate_visit"));
        assertEquals(
                6, count(dataSource, "select count(*) from ops.gate_visit where in_at is not null and out_at is null"));
        given().get("/api/v1/ports/westports/state/snapshot")
                .then()
                .statusCode(200)
                .body("openGateVisits", equalTo(6));
    }

    @Test
    void anExitWithoutAnEntryCreatesAVisitWithAnUnknownEntryThatIsNotOpen() {
        tx("TV-9", "OUT");

        assertEquals(
                List.of("TV-9 null L1"),
                lines(
                        dataSource,
                        "select truck_visit_id || ' ' || coalesce(in_at::text, 'null') || ' ' || lane "
                                + "from ops.gate_visit"));
        assertEquals(
                0, count(dataSource, "select count(*) from ops.gate_visit where in_at is not null and out_at is null"));
        given().get("/api/v1/ports/westports/state/snapshot").then().body("openGateVisits", equalTo(0));
    }

    @Test
    void everyTransactionIsAStateChangeOfTheVisitWithItsOwnVersion() {
        tx("TV-1", "IN");
        tx("TV-1", "OUT");

        assertEquals(
                List.of("gate-visit 0", "gate-visit 1"),
                lines(
                        dataSource,
                        "select entity_type || ' ' || entity_version from ops.state_change order by sequence"));
        assertEquals(
                List.of("TV-1:" + h.layout.gate()),
                lines(dataSource, "select distinct entity_id from ops.state_change"));
    }

    @Test
    void aGateThatIsNotInThePublishedLayoutIsDeadLettered() {
        h.process(h.events.gate("TV-1", h.layout.berth(), "IN", null));

        assertEquals(0, count(dataSource, "select count(*) from ops.gate_visit"));
        assertEquals(
                List.of("UNKNOWN_ENTITY"),
                lines(dataSource, "select substring(error from '^[A-Z_]+') from ops.dead_letter"));
    }
}
