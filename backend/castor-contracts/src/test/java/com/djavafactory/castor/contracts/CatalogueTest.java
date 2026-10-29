package com.djavafactory.castor.contracts;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogueTest {

    @Test
    void catalogueHasTheElevenTypesOfV1() {
        assertEquals(
                Set.of(
                        "vessel.call.updated",
                        "equipment.status.changed",
                        "equipment.position.observed",
                        "equipment.assignment.changed",
                        "cargo.lot.registered",
                        "cargo.lot.moved",
                        "storage.level.observed",
                        "flow.rate.observed",
                        "inventory.adjusted",
                        "gate.transaction",
                        "truck.approach.observed"),
                java.util.EnumSet.allOf(EventType.class).stream()
                        .map(EventType::code)
                        .collect(Collectors.toSet()));
    }

    @ParameterizedTest
    @EnumSource(EventType.class)
    void everyTypeHasASchemaAndBothExamples(EventType type) {
        assertNotNull(EventType.class.getResource(type.schemaResource()), "schema of " + type);
        assertNotNull(Examples.valid(type));
        assertNotNull(Examples.invalid(type));
        assertEquals(type, EventType.fromCode(type.code()).orElseThrow());
    }

    @Test
    void unknownCodeIsNotInTheCatalogue() {
        assertTrue(EventType.fromCode("vessel.exploded").isEmpty());
        assertTrue(EventType.fromCode(null).isEmpty());
    }

    @Test
    void topicsFollowTheContract() {
        assertEquals("castor.ingest.canonical.v1", Topics.INGEST_CANONICAL);
        assertEquals("castor.state.changed.v1", Topics.STATE_CHANGED);
        assertEquals("castor.alerts.v1", Topics.ALERTS);
        assertEquals("castor.integration.dlq.v1", Topics.INTEGRATION_DLQ);
        assertEquals(
                List.of(
                        new Topics.Spec(Topics.INGEST_CANONICAL, 6, Duration.ofDays(7), "aggregateId"),
                        new Topics.Spec(Topics.STATE_CHANGED, 3, Duration.ofDays(1), "portId"),
                        new Topics.Spec(Topics.ALERTS, 3, Duration.ofDays(30), "portId"),
                        new Topics.Spec(Topics.INTEGRATION_DLQ, 3, Duration.ofDays(30), "aggregateId")),
                Topics.ALL);
    }
}
