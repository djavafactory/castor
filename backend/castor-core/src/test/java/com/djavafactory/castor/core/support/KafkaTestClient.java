package com.djavafactory.castor.core.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import com.djavafactory.castor.contracts.CanonicalEvent;

/** Publishes canonical events to a topic and reads a topic back, for the tests that go through the real broker. */
public final class KafkaTestClient {

    private final String bootstrapServers;

    public KafkaTestClient(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /** Publishes the events in order, keyed by aggregateId like the gateway does, and waits for the broker. */
    public void publish(String topic, List<CanonicalEvent> events) {
        try (var producer = new KafkaProducer<>(
                Map.<String, Object>of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        bootstrapServers,
                        ProducerConfig.ACKS_CONFIG,
                        "all",
                        ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                        true),
                new StringSerializer(),
                new ByteArraySerializer())) {
            for (CanonicalEvent event : events) {
                producer.send(new ProducerRecord<>(topic, event.aggregateId(), TestEvents.bytes(event)))
                        .get(10, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish to " + topic, e);
        }
    }

    public void publishRaw(String topic, String key, byte[] value) {
        try (var producer = new KafkaProducer<>(
                Map.<String, Object>of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers),
                new StringSerializer(),
                new ByteArraySerializer())) {
            producer.send(new ProducerRecord<>(topic, key, value)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish to " + topic, e);
        }
    }

    /** The topic from the beginning, read with a consumer group of its own until {@code done} says enough. */
    public List<ConsumerRecord<String, byte[]>> readUntil(
            String topic, Predicate<List<ConsumerRecord<String, byte[]>>> done, Duration within) {
        List<ConsumerRecord<String, byte[]>> found = new ArrayList<>();
        try (var consumer = new KafkaConsumer<>(
                Map.<String, Object>of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        bootstrapServers,
                        ConsumerConfig.GROUP_ID_CONFIG,
                        "test-" + UUID.randomUUID(),
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        "earliest"),
                new StringDeserializer(),
                new ByteArrayDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + within.toNanos();
            while (!done.test(found) && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(found::add);
            }
        }
        return found;
    }
}
