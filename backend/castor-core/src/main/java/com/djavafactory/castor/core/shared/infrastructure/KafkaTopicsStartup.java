package com.djavafactory.castor.core.shared.infrastructure;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.djavafactory.castor.contracts.TopicProvisioner;

/**
 * Creates the missing Kafka topics at start. Existing topics are never touched, so a second start (or a second service)
 * changes nothing. If the broker does not answer within {@code castor.kafka.topics-timeout} the start fails, like an
 * unreachable database does.
 */
@ApplicationScoped
public class KafkaTopicsStartup {

    private static final Logger LOG = Logger.getLogger(KafkaTopicsStartup.class);

    private final String bootstrapServers;
    private final CastorConfig castor;

    public KafkaTopicsStartup(
            @ConfigProperty(name = "kafka.bootstrap.servers") String bootstrapServers, CastorConfig castor) {
        this.bootstrapServers = bootstrapServers;
        this.castor = castor;
    }

    void onStart(@Observes StartupEvent event) {
        TopicProvisioner.Result result = provisioner().ensureTopics();
        LOG.infof("Kafka topics ready: created %s, already present %s", result.created(), result.existing());
    }

    /** The provisioner this application uses; tests call it directly to repeat the start-up step. */
    public TopicProvisioner provisioner() {
        return new TopicProvisioner(
                bootstrapServers,
                castor.kafka().replicationFactor(),
                castor.kafka().topicsTimeout());
    }
}
