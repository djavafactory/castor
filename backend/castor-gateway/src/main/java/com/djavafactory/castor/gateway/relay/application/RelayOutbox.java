package com.djavafactory.castor.gateway.relay.application;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.context.ApplicationScoped;

import io.micrometer.core.instrument.MeterRegistry;
import org.jboss.logging.Logger;

import com.djavafactory.castor.gateway.relay.domain.EventPublisher;
import com.djavafactory.castor.gateway.relay.domain.OutboxMessage;
import com.djavafactory.castor.gateway.relay.domain.OutboxStore;
import com.djavafactory.castor.gateway.relay.domain.PublishOrder;
import com.djavafactory.castor.gateway.shared.domain.FailPoint;
import com.djavafactory.castor.gateway.shared.domain.FailPoints;
import com.djavafactory.castor.gateway.shared.infrastructure.CastorConfig;

/**
 * The outbox relay. A run publishes the oldest unpublished events to Kafka and marks them published only after the
 * broker acknowledged them. If the process dies between the acknowledgement and the mark, the next run sends the event
 * again with the same eventId; the consumer drops it by that id (STATE-03). If the broker is away nothing is marked and
 * the outbox simply grows until it is back.
 */
@ApplicationScoped
public class RelayOutbox {

    private static final Logger LOG = Logger.getLogger(RelayOutbox.class);

    private final OutboxStore store;
    private final EventPublisher publisher;
    private final FailPoints failPoints;
    private final MeterRegistry meters;
    private final int batchSize;
    private final AtomicBoolean brokerAnswering = new AtomicBoolean(true);

    public RelayOutbox(
            OutboxStore store,
            EventPublisher publisher,
            FailPoints failPoints,
            MeterRegistry meters,
            CastorConfig castor) {
        this.store = store;
        this.publisher = publisher;
        this.failPoints = failPoints;
        this.meters = meters;
        this.batchSize = castor.relay().batchSize();
    }

    /** Publishes one batch and returns how many events were marked published. */
    public int runOnce() {
        int published = store.relay(batchSize, this::send);
        if (published > 0) {
            meters.counter("castor.gateway.published").increment(published);
        }
        return published;
    }

    private List<Long> send(List<OutboxMessage> messages) {
        // Only when there is something to send, for the same reason as before-normalize
        failPoints.check(FailPoint.BEFORE_RELAY);
        List<Boolean> acknowledged = publisher.publish(messages);
        List<Long> ids = PublishOrder.markable(messages, acknowledged);
        report(messages.size(), ids.size());
        failPoints.check(FailPoint.AFTER_SEND_BEFORE_MARK);
        return ids;
    }

    private void report(int sent, int marked) {
        if (marked < sent) {
            if (brokerAnswering.getAndSet(false)) {
                LOG.warnf(
                        "Kafka did not acknowledge %d of %d events; they stay in the outbox and are sent again",
                        sent - marked, sent);
            }
        } else if (!brokerAnswering.getAndSet(true)) {
            LOG.infof("Kafka acknowledges again: the outbox is being published (%d events in this run)", sent);
        }
    }
}
