package com.djavafactory.castor.core.shared.infrastructure;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import io.quarkus.test.junit.QuarkusTest;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import com.djavafactory.castor.contracts.TopicProvisioner;
import com.djavafactory.castor.contracts.Topics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Dev Services broker already went through {@link KafkaTopicsStartup} when the application started. */
@QuarkusTest
class TopicProvisionerIT {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @Inject
    KafkaTopicsStartup startup;

    @Test
    void startingTheApplicationCreatedTheFourTopicsOnce() throws Exception {
        try (Admin admin = admin()) {
            Map<String, TopicDescription> topics = admin.describeTopics(
                            Topics.ALL.stream().map(Topics.Spec::name).toList())
                    .allTopicNames()
                    .get(10, TimeUnit.SECONDS);

            assertEquals(4, topics.size());
            for (Topics.Spec spec : Topics.ALL) {
                TopicDescription topic = topics.get(spec.name());
                assertEquals(spec.partitions(), topic.partitions().size(), spec.name() + " partitions");
                assertTrue(
                        topic.partitions().stream().allMatch(p -> p.replicas().size() == 1),
                        spec.name() + " replication factor 1");
                assertEquals(
                        Long.toString(spec.retention().toMillis()),
                        retentionMs(admin, spec.name()),
                        spec.name() + " retention");
            }
        }
    }

    @Test
    void secondStartCreatesNothingAndKeepsTheSameTopics() throws Exception {
        try (Admin admin = admin()) {
            Map<String, org.apache.kafka.common.Uuid> idsBefore = topicIds(admin);

            TopicProvisioner.Result second = startup.provisioner().ensureTopics();
            TopicProvisioner.Result third = startup.provisioner().ensureTopics();

            assertEquals(List.of(), second.created());
            assertEquals(List.of(), third.created());
            assertEquals(
                    Set.of(Topics.INGEST_CANONICAL, Topics.STATE_CHANGED, Topics.ALERTS, Topics.INTEGRATION_DLQ),
                    Set.copyOf(second.existing()));
            assertEquals(idsBefore, topicIds(admin), "no topic was deleted and recreated");
        }
    }

    @Test
    void neverAltersATopicThatExists() throws Exception {
        ConfigResource alerts = new ConfigResource(ConfigResource.Type.TOPIC, Topics.ALERTS);
        try (Admin admin = admin()) {
            String original = retentionMs(admin, Topics.ALERTS);
            setRetention(admin, alerts, "123456789");
            try {
                startup.provisioner().ensureTopics();

                assertEquals("123456789", retentionMs(admin, Topics.ALERTS));
            } finally {
                setRetention(admin, alerts, original);
            }
        }
    }

    private Admin admin() {
        return Admin.create(Map.of("bootstrap.servers", bootstrapServers));
    }

    private static Map<String, org.apache.kafka.common.Uuid> topicIds(Admin admin) throws Exception {
        Map<String, org.apache.kafka.common.Uuid> ids = new java.util.HashMap<>();
        admin.describeTopics(Topics.ALL.stream().map(Topics.Spec::name).toList())
                .allTopicNames()
                .get(10, TimeUnit.SECONDS)
                .forEach((name, description) -> ids.put(name, description.topicId()));
        return ids;
    }

    private static String retentionMs(Admin admin, String topic) throws Exception {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        return admin.describeConfigs(List.of(resource))
                .all()
                .get(10, TimeUnit.SECONDS)
                .get(resource)
                .get(TopicConfig.RETENTION_MS_CONFIG)
                .value();
    }

    private static void setRetention(Admin admin, ConfigResource resource, String value) throws Exception {
        admin.incrementalAlterConfigs(Map.of(
                        resource,
                        List.of(new AlterConfigOp(
                                new ConfigEntry(TopicConfig.RETENTION_MS_CONFIG, value), AlterConfigOp.OpType.SET))))
                .all()
                .get(10, TimeUnit.SECONDS);
    }

    @Test
    void failsWithAClearErrorWhenTheBrokerDoesNotAnswer() {
        TopicProvisioner unreachable = new TopicProvisioner("127.0.0.1:1", (short) 1, Duration.ofSeconds(1));

        IllegalStateException failure =
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, unreachable::ensureTopics);

        assertTrue(failure.getMessage().startsWith("Cannot create Kafka topics"), failure.getMessage());
    }
}
