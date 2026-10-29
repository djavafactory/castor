package com.djavafactory.castor.core.shared.infrastructure;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

/**
 * Ready only while a broker answers. The question is asked with a short timeout of its own
 * ({@code castor.kafka.health-timeout}), so a stopped broker turns readiness DOWN within seconds instead of holding the
 * probe for the Kafka client's default minute. Liveness never looks at Kafka.
 */
@Readiness
@ApplicationScoped
public class KafkaReadinessCheck implements HealthCheck {

    private static final Logger LOG = Logger.getLogger(KafkaReadinessCheck.class);

    private final String bootstrapServers;
    private final Duration timeout;
    private volatile Admin admin;

    public KafkaReadinessCheck(
            @ConfigProperty(name = "kafka.bootstrap.servers") String bootstrapServers, CastorConfig castor) {
        this.bootstrapServers = bootstrapServers;
        this.timeout = castor.kafka().healthTimeout();
    }

    @Override
    public HealthCheckResponse call() {
        var builder = HealthCheckResponse.named("Kafka");
        try {
            int brokers = admin().describeCluster()
                    .nodes()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .size();
            return (brokers > 0 ? builder.up() : builder.down())
                    .withData("brokers", brokers)
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return builder.down().withData("reason", "interrupted").build();
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            LOG.debugf(e, "Kafka not reachable at %s", bootstrapServers);
            return builder.down().withData("reason", "broker not reachable").build();
        }
    }

    private Admin admin() {
        Admin current = admin;
        if (current == null) {
            synchronized (this) {
                if (admin == null) {
                    admin = Admin.create(Map.of(
                            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) timeout.toMillis(),
                            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) timeout.toMillis()));
                }
                current = admin;
            }
        }
        return current;
    }

    @PreDestroy
    void close() {
        Admin current = admin;
        if (current != null) {
            current.close(Duration.ofSeconds(1));
        }
    }
}
