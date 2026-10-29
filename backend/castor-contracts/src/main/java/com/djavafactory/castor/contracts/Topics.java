package com.djavafactory.castor.contracts;

import java.time.Duration;
import java.util.List;

/**
 * The Kafka topics of schema version 1. A breaking contract change means schemas/v2 and new topic names (".v2"), never
 * a change to these.
 */
public final class Topics {

    /** Canonical events from the gateway. Key: aggregateId. */
    public static final String INGEST_CANONICAL = "castor.ingest.canonical.v1";

    /** State changes published by core. Key: portId. */
    public static final String STATE_CHANGED = "castor.state.changed.v1";

    public static final String ALERTS = "castor.alerts.v1";

    /** Events no service could process. */
    public static final String INTEGRATION_DLQ = "castor.integration.dlq.v1";

    public static final List<Spec> ALL = List.of(
            new Spec(INGEST_CANONICAL, 6, Duration.ofDays(7), "aggregateId"),
            new Spec(STATE_CHANGED, 3, Duration.ofDays(1), "portId"),
            new Spec(ALERTS, 3, Duration.ofDays(30), "portId"),
            new Spec(INTEGRATION_DLQ, 3, Duration.ofDays(30), "aggregateId"));

    private Topics() {}

    /**
     * @param key what the record key is
     */
    public record Spec(String name, int partitions, Duration retention, String key) {}
}
