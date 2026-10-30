package com.djavafactory.castor.core.operations.application;

import java.time.InstantSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import org.jboss.logging.Logger;

import com.djavafactory.castor.contracts.Topics;
import com.djavafactory.castor.core.operations.domain.StateChange;
import com.djavafactory.castor.core.operations.domain.StateChangeRepository;
import com.djavafactory.castor.core.shared.infrastructure.CastorConfig;

/**
 * Publishes the rows of ops.state_change that have no published_at to castor.state.changed.v1, oldest first, key = the
 * port code, then marks them published. For consumers other than the browser hub, which reads the table itself.
 *
 * <p>Delivery is at least once: a crash between the send and the mark sends the same change again, and a consumer
 * tells it by {@code sequence}. One instance publishes a port at a time (an advisory lock held for the batch), and the
 * relay stops at the first send that fails, so the order of a port never breaks.
 */
@ApplicationScoped
public class StateChangePublisher {

    private static final Logger LOG = Logger.getLogger(StateChangePublisher.class);

    private final StateChangeRepository changes;
    private final EventSender sender;
    private final InstantSource clock;
    private final ObjectMapper json;
    private final int batch;

    StateChangePublisher(
            StateChangeRepository changes,
            EventSender sender,
            InstantSource clock,
            ObjectMapper json,
            CastorConfig castor) {
        this.changes = changes;
        this.sender = sender;
        this.clock = clock;
        this.json = json;
        this.batch = castor.state().publisher().batch();
    }

    /** Publishes what is waiting, one batch per port; the number of changes sent. */
    public int publishPending() {
        Map<Long, String> ports = QuarkusTransaction.requiringNew().call(changes::portsWithUnpublished);
        int sent = 0;
        for (Map.Entry<Long, String> port : ports.entrySet()) {
            sent += QuarkusTransaction.requiringNew().call(() -> publish(port.getKey(), port.getValue()));
        }
        return sent;
    }

    private int publish(long portId, String portCode) {
        if (!changes.tryLockPublishing(portId)) {
            return 0;
        }
        List<StateChange> pending = changes.unpublished(portId, batch);
        long last = 0;
        int sent = 0;
        for (StateChange change : pending) {
            try {
                sender.send(
                        Topics.STATE_CHANGED,
                        portCode,
                        json.writeValueAsBytes(message(portCode, change)),
                        Map.of("entityType", change.entityType()));
            } catch (JsonProcessingException | RuntimeException e) {
                LOG.warnf(e, "Publishing %s change %d failed; it is tried again", portCode, change.sequence());
                break;
            }
            last = change.sequence();
            sent++;
        }
        if (sent > 0) {
            changes.markPublished(portId, last, clock.instant());
        }
        return sent;
    }

    private static Map<String, Object> message(String portCode, StateChange change) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("portId", portCode);
        message.put("sequence", change.sequence());
        message.put("entityType", change.entityType());
        message.put("entityId", change.entityId());
        message.put("entityVersion", change.entityVersion());
        message.put("occurredAt", change.occurredAt().toString());
        message.put("data", change.body());
        return message;
    }
}
