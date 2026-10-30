package com.djavafactory.castor.gateway.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;

import jakarta.inject.Inject;

import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;

import com.djavafactory.castor.gateway.mapping.domain.Mappings;

import static io.restassured.RestAssured.given;

/**
 * What the tests of the system sources share: an empty identity mapping table (the demo seed is not loaded in tests, so
 * each test says which assets exist), a fresh mapping cache, and posting a native record to a source.
 */
public abstract class MappingSupport extends PipelineSupport {

    @Inject
    protected Mappings mappings;

    @BeforeEach
    void cleanMappings() {
        Db.execute(dataSource, "delete from ingest.identity_mapping");
        mappings.invalidate();
    }

    protected void mapAsset(String system, String type, String sourceId, String canonicalId) {
        mapAsset(system, type, sourceId, canonicalId, Instant.EPOCH, null);
    }

    protected void mapAsset(String system, String type, String sourceId, String canonicalId, Instant from, Instant to) {
        Db.execute(
                dataSource,
                "insert into ingest.identity_mapping (port_id, source_system, source_entity_type, source_id, "
                        + "canonical_id, effective_from, effective_to, created_by, created_at) "
                        + "values ('westports', ?, ?, ?, ?, ?, ?, 'test', now())",
                system,
                type,
                sourceId,
                canonicalId,
                Timestamp.from(from),
                to == null ? null : Timestamp.from(to));
        mappings.invalidate();
    }

    protected static String path(String source) {
        return "/api/v1/ports/westports/integrations/" + source + "/events";
    }

    /** Posts a record to a source with the demo key. */
    protected Response postTo(String source, String body) {
        return post(path(source), demoKey, body);
    }

    protected Response postText(String source, String body) {
        return given().contentType("text/plain")
                .header("X-Castor-Key", demoKey)
                .body(body)
                .post(path(source));
    }

    /** Posts a record, normalizes and returns the reception id. */
    protected String accepted(String source, String body) {
        return postTo(source, body).then().statusCode(202).extract().path("receptionId");
    }

    protected String quarantineReason(String receptionId) {
        return Db.value(
                dataSource, "select reason_code from ingest.quarantine where reception_id = ?::uuid", receptionId);
    }

    protected long outboxRows() {
        return Db.count(dataSource, "select count(*) from ingest.outbox");
    }

    protected Set<String> kafkaSourceEvents(Set<String> sourceEventIds) {
        var found = new java.util.HashSet<String>();
        KafkaProbe.forSourceEvents(bootstrapServers, sourceEventIds)
                .forEach(m -> found.add(m.event().sourceEventId()));
        return found;
    }
}
