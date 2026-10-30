package com.djavafactory.castor.core.operations.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.Quality;
import com.djavafactory.castor.contracts.Quantity;
import com.djavafactory.castor.contracts.Unit;
import com.djavafactory.castor.core.operations.domain.CanonicalEventRecord;
import com.djavafactory.castor.core.operations.domain.CargoLot;
import com.djavafactory.castor.core.operations.domain.CargoMovement;
import com.djavafactory.castor.core.operations.domain.CargoRepository;
import com.djavafactory.castor.core.operations.domain.EventLogRepository;
import com.djavafactory.castor.core.operations.domain.Provenance;
import com.djavafactory.castor.core.operations.domain.VesselCall;
import com.djavafactory.castor.core.operations.domain.VesselCallRepository;
import com.djavafactory.castor.core.operations.domain.VesselStatus;
import com.djavafactory.castor.core.support.LayoutTestSupport;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/** Vessel calls (STATE-02 part 1): filters, the event timeline and the cargo progress per family. */
@QuarkusTest
@TestSecurity(user = "viewer", roles = "VIEWER")
class VesselCallResourceIT {

    private static final Instant T0 = Instant.parse("2026-10-30T08:00:00Z");

    @Inject
    DataSource dataSource;

    @Inject
    VesselCallRepository calls;

    @Inject
    CargoRepository cargo;

    @Inject
    EventLogRepository events;

    private long port;
    private UUID berth;
    private UUID pad;

    @BeforeEach
    void setUp() throws Exception {
        LayoutTestSupport.clear(dataSource);
        try (var c = dataSource.getConnection()) {
            port = LayoutTestSupport.portId(c);
            berth = LayoutTestSupport.asset(c, "B1", "BERTH");
            pad = LayoutTestSupport.asset(c, "PAD1", "STORAGE_PAD");
        }
    }

    @AfterEach
    void tearDown() {
        LayoutTestSupport.clear(dataSource);
    }

    private VesselCall call(
            String code, VesselStatus status, Instant eta, Instant ata, Instant etd, Instant atd, UUID berthId) {
        return QuarkusTransaction.requiringNew()
                .call(() -> calls.insert(new VesselCall(
                        null,
                        port,
                        code,
                        "MV " + code,
                        null,
                        null,
                        null,
                        status,
                        eta,
                        ata,
                        etd,
                        atd,
                        berthId,
                        new Provenance("CBAS", Quality.SIMULATED, T0),
                        T0,
                        0)));
    }

    @Test
    void callsAreFilteredByStatusAndByTheTimeTheyOverlap() {
        call("C-OLD", VesselStatus.DEPARTED, null, T0.minusSeconds(86400), null, T0.minusSeconds(80000), berth);
        call("C-NOW", VesselStatus.ALONGSIDE, null, T0.minusSeconds(3600), T0.plusSeconds(7200), null, berth);
        call("C-NEXT", VesselStatus.EXPECTED, T0.plusSeconds(86400), null, null, null, null);

        given().when()
                .get("/api/v1/ports/westports/vessel-calls")
                .then()
                .statusCode(200)
                .body("callCode", contains("C-NEXT", "C-NOW", "C-OLD"));
        given().queryParam("status", "alongside")
                .when()
                .get("/api/v1/ports/westports/vessel-calls")
                .then()
                .body("callCode", contains("C-NOW"));
        given().queryParam("from", T0.toString())
                .queryParam("to", T0.plusSeconds(3600).toString())
                .when()
                .get("/api/v1/ports/westports/vessel-calls")
                .then()
                .body("callCode", contains("C-NOW"));
        given().queryParam("status", "LATE")
                .when()
                .get("/api/v1/ports/westports/vessel-calls")
                .then()
                .statusCode(400);
        given().queryParam("from", "yesterday")
                .when()
                .get("/api/v1/ports/westports/vessel-calls")
                .then()
                .statusCode(400);
    }

    @Test
    void aCallShowsItsTimelineAndTheCargoMovedAtItsBerthPerFamily() {
        VesselCall alongside = call("C-1", VesselStatus.DEPARTED, null, T0, null, T0.plusSeconds(3600), berth);
        QuarkusTransaction.requiringNew().run(() -> {
            events.append(event(alongside.id().toString(), "vessel.call.updated", T0));
            events.append(event("C-1", "vessel.call.updated", T0.minusSeconds(600)));
            events.append(event("someone-else", "vessel.call.updated", T0));
            UUID coal = lot("LOT-COAL", "DRY_BULK", Unit.TONNES, "1000");
            UUID oil = lot("LOT-OIL", "LIQUID_BULK", Unit.CUBIC_METRES, "400");
            move(coal, "300", Unit.TONNES, T0.plusSeconds(60));
            move(coal, "200", Unit.TONNES, T0.plusSeconds(120));
            move(oil, "50", Unit.CUBIC_METRES, T0.plusSeconds(180));
            // before the ship arrived: not this call
            move(oil, "70", Unit.CUBIC_METRES, T0.minusSeconds(3600));
        });

        given().when()
                .get("/api/v1/ports/westports/vessel-calls/" + alongside.id())
                .then()
                .statusCode(200)
                .body("call.callCode", equalTo("C-1"))
                .body("call.freshnessSlaS", equalTo(900))
                .body("timeline", hasSize(2))
                .body("timeline[0].occurredAt", equalTo("2026-10-30T07:50:00Z"))
                .body("timeline[1].source", equalTo("cbas"))
                .body("cargo", hasSize(2))
                .body("cargo[0].cargoFamily", equalTo("DRY_BULK"))
                .body("cargo[0].unit", equalTo("TONNES"))
                .body("cargo[0].registered", equalTo(1000.0f))
                .body("cargo[0].moved", equalTo(500.0f))
                .body("cargo[1].cargoFamily", equalTo("LIQUID_BULK"))
                .body("cargo[1].unit", equalTo("CUBIC_METRES"))
                .body("cargo[1].moved", equalTo(50.0f));
        given().when()
                .get("/api/v1/ports/westports/vessel-calls/" + UUID.randomUUID())
                .then()
                .statusCode(404);
        given().when()
                .get("/api/v1/ports/westports/vessel-calls/not-an-id")
                .then()
                .statusCode(404);
    }

    @Test
    void aCallThatHasNotArrivedHasNoCargoProgress() {
        VesselCall expected = call("C-2", VesselStatus.EXPECTED, T0.plusSeconds(60), null, null, null, berth);
        given().when()
                .get("/api/v1/ports/westports/vessel-calls/" + expected.id())
                .then()
                .statusCode(200)
                .body("cargo", hasSize(0));
    }

    private CanonicalEventRecord event(String aggregate, String type, Instant at) {
        return new CanonicalEventRecord(
                UUID.randomUUID(), port, type, aggregate, at, at, Quality.SIMULATED, Map.of("sourceSystem", "cbas"));
    }

    private UUID lot(String code, String family, Unit unit, String registered) {
        return cargo.insertLot(new CargoLot(
                        null,
                        port,
                        code,
                        null,
                        family,
                        code,
                        unit,
                        new BigDecimal(registered),
                        null,
                        null,
                        null,
                        "ITAP",
                        Quality.SIMULATED,
                        0))
                .id();
    }

    private void move(UUID lot, String quantity, Unit unit, Instant at) {
        cargo.appendMovement(new CargoMovement(
                port, lot, berth, pad, new Quantity(new BigDecimal(quantity), unit), null, at, UUID.randomUUID()));
    }
}
