package com.djavafactory.castor.core.layout.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.core.layout.infrastructure.LayoutSeedJob;
import com.djavafactory.castor.core.support.LayoutTestSupport;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DESIGNER-04 part 1 acceptance: a write into the Westports draft takes under 150 ms at the 95th percentile. */
@QuarkusTest
@TestSecurity(user = "editor", roles = "EDITOR")
class LayoutAssetWriteSpeedIT {

    private static final int WRITES = 40;
    private static final long P95_LIMIT_MS = 150;

    @Inject
    LayoutSeedJob seed;

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper json;

    private UUID draftId;

    @BeforeEach
    void westportsDraft() throws SQLException {
        LayoutTestSupport.clear(dataSource);
        seed.seed();
        try (Connection c = dataSource.getConnection()) {
            draftId = LayoutTestSupport.version(c, 2, "DRAFT");
            c.createStatement()
                    .execute("select layout.copy_revisions((select id from layout.layout_version "
                            + "where version_no = 1), '" + draftId + "')");
        }
    }

    @AfterEach
    void clear() {
        LayoutTestSupport.clear(dataSource);
    }

    @Test
    void createAndPatchStayUnder150MsAtTheNinetyFifthPercentile() throws Exception {
        JsonNode draft = json.readTree(given().get("/api/v1/ports/westports/layouts/draft")
                .then()
                .statusCode(200)
                .extract()
                .asByteArray());
        JsonNode hall = null;
        for (JsonNode f : draft.get("features").get("features")) {
            if ("CONTAINER_YARD".equals(f.get("properties").get("type").asText())) {
                hall = f;
                break;
            }
        }
        JsonNode anchor = hall.get("properties").get("anchor");
        String base = "/api/v1/layouts/" + draftId + "/assets";

        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < WRITES; i++) {
            var body = json.createObjectNode();
            body.put("type", "FORKLIFT");
            body.put("name", "Forklift " + i);
            body.putArray("anchor")
                    .add(anchor.get(0).asDouble())
                    .add(anchor.get(1).asDouble());
            body.put("headingDeg", i * 9);
            long t0 = System.nanoTime();
            String id = given().contentType("application/json")
                    .body(body)
                    .post(base)
                    .then()
                    .statusCode(201)
                    .extract()
                    .path("feature.id");
            millis.add((System.nanoTime() - t0) / 1_000_000);

            t0 = System.nanoTime();
            given().contentType("application/json")
                    .header("If-Match", "\"0\"")
                    .body("{\"name\":\"Moved forklift " + i + "\",\"headingDeg\":" + (i * 9 + 5) + "}")
                    .patch(base + "/" + id)
                    .then()
                    .statusCode(200);
            millis.add((System.nanoTime() - t0) / 1_000_000);
        }
        Collections.sort(millis);
        long p95 = millis.get((int) Math.ceil(millis.size() * 0.95) - 1);
        assertTrue(p95 < P95_LIMIT_MS, "p95 of " + millis.size() + " writes was " + p95 + " ms: " + millis);
    }
}
