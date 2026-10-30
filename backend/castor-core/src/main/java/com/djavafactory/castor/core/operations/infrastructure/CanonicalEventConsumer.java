package com.djavafactory.castor.core.operations.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;

import io.smallrye.reactive.messaging.annotations.Blocking;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import com.djavafactory.castor.core.operations.application.CanonicalEventProcessor;
import com.djavafactory.castor.core.operations.application.IncomingRecord;

/**
 * The channel {@code canonical} (castor.ingest.canonical.v1, group castor-core-projector, see application.properties).
 * Blocking and ordered: one record at a time, and the offset is committed only after the method returns, that is after
 * the event's transaction committed or the event was dead-lettered. An exception here stops the channel (readiness goes
 * DOWN) and leaves the offset where it was.
 */
@ApplicationScoped
public class CanonicalEventConsumer {

    private final CanonicalEventProcessor processor;

    CanonicalEventConsumer(CanonicalEventProcessor processor) {
        this.processor = processor;
    }

    @Incoming("canonical")
    @Blocking(ordered = true)
    public void consume(ConsumerRecord<String, byte[]> record) {
        processor.process(
                new IncomingRecord(record.topic(), record.partition(), record.offset(), record.key(), record.value()));
    }
}
