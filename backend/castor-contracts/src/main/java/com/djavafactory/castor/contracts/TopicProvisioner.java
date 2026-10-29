package com.djavafactory.castor.contracts;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.TopicExistsException;

/**
 * Creates the {@link Topics#ALL} that are missing. It never deletes or alters a topic that exists, so every service
 * can call it at start-up, also at the same time: a topic another service created a moment earlier counts as existing.
 */
public final class TopicProvisioner {

    private final Map<String, Object> adminConfig;
    private final short replicationFactor;
    private final Duration timeout;

    /**
     * @param bootstrapServers the Kafka bootstrap servers, comma separated
     * @param replicationFactor 1 in local and dev
     * @param timeout how long the broker may take to answer before provisioning fails
     */
    public TopicProvisioner(String bootstrapServers, short replicationFactor, Duration timeout) {
        this.adminConfig = adminConfig(bootstrapServers, timeout);
        this.replicationFactor = replicationFactor;
        this.timeout = timeout;
    }

    /**
     * @param created topics this call created
     * @param existing topics that were already there, left untouched
     */
    public record Result(List<String> created, List<String> existing) {}

    public Result ensureTopics() {
        try (Admin admin = Admin.create(adminConfig)) {
            Set<String> present = admin.listTopics().names().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            List<NewTopic> missing = new ArrayList<>();
            List<String> existing = new ArrayList<>();
            for (Topics.Spec spec : Topics.ALL) {
                if (present.contains(spec.name())) {
                    existing.add(spec.name());
                } else {
                    missing.add(newTopic(spec));
                }
            }
            List<String> created = new ArrayList<>();
            if (!missing.isEmpty()) {
                CreateTopicsResult result = admin.createTopics(missing);
                for (Map.Entry<String, KafkaFuture<Void>> entry :
                        result.values().entrySet()) {
                    if (createdBySomeoneElse(entry.getValue())) {
                        existing.add(entry.getKey());
                    } else {
                        created.add(entry.getKey());
                    }
                }
            }
            return new Result(List.copyOf(created), List.copyOf(existing));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating Kafka topics", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Cannot create Kafka topics: " + e.getMessage(), e);
        }
    }

    /** True when the topic appeared between the listing and the creation. */
    private boolean createdBySomeoneElse(KafkaFuture<Void> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return false;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TopicExistsException) {
                return true;
            }
            throw e;
        }
    }

    private NewTopic newTopic(Topics.Spec spec) {
        return new NewTopic(spec.name(), spec.partitions(), replicationFactor)
                .configs(Map.of(
                        TopicConfig.RETENTION_MS_CONFIG,
                        Long.toString(spec.retention().toMillis())));
    }

    private static Map<String, Object> adminConfig(String bootstrapServers, Duration timeout) {
        Map<String, Object> config = new HashMap<>();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) timeout.toMillis());
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) timeout.toMillis());
        return config;
    }
}
