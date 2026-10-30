package com.djavafactory.castor.gateway.shared.infrastructure;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.djavafactory.castor.contracts.TopicProvisioner;

/**
 * Creates the missing Kafka topics (TopicProvisioner never alters or deletes). Unlike castor-core the gateway does not
 * refuse to start when the broker is away: intake only needs the database, accepted events wait in the outbox, and the
 * relay calls {@link #ensure()} before it sends, so the topics exist with their configured partitions before the
 * first message (the broker would otherwise create a one-partition topic on first use).
 */
@ApplicationScoped
public class KafkaTopics {

    private static final Logger LOG = Logger.getLogger(KafkaTopics.class);

    private final String bootstrapServers;
    private final CastorConfig castor;
    private volatile boolean ready;

    public KafkaTopics(@ConfigProperty(name = "kafka.bootstrap.servers") String bootstrapServers, CastorConfig castor) {
        this.bootstrapServers = bootstrapServers;
        this.castor = castor;
    }

    void onStart(@Observes StartupEvent event) {
        try {
            ensure();
        } catch (RuntimeException e) {
            LOG.warnf("Kafka topics not created at start, the relay retries: %s", e.getMessage());
        }
    }

    /** Creates the missing topics once; throws while the broker does not answer within the topics timeout. */
    public void ensure() {
        ensure(castor.kafka().topicsTimeout());
    }

    /** Same, but gives up after {@code timeout}: the relay must not wait longer than one send does. */
    public void ensure(Duration timeout) {
        if (ready) {
            return;
        }
        synchronized (this) {
            if (!ready) {
                TopicProvisioner.Result result = provisioner(timeout).ensureTopics();
                LOG.infof("Kafka topics ready: created %s, already present %s", result.created(), result.existing());
                ready = true;
            }
        }
    }

    /** The provisioner this application uses; tests call it directly to repeat the start-up step. */
    public TopicProvisioner provisioner() {
        return provisioner(castor.kafka().topicsTimeout());
    }

    private TopicProvisioner provisioner(Duration timeout) {
        return new TopicProvisioner(bootstrapServers, castor.kafka().replicationFactor(), timeout);
    }
}
