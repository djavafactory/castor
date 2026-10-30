package com.djavafactory.castor.gateway.support;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import com.djavafactory.castor.contracts.CanonicalEvent;
import com.djavafactory.castor.contracts.CanonicalEventCodec;
import com.djavafactory.castor.contracts.Topics;

/** Reads everything on castor.ingest.canonical.v1 from the beginning, without joining a consumer group. */
public final class KafkaProbe {

    /** One record of the topic. */
    public record Message(String key, String eventId, String type, int partition, long offset, CanonicalEvent event) {}

    private KafkaProbe() {}

    public static List<Message> all(String bootstrapServers) {
        var codec = new CanonicalEventCodec();
        try (var consumer = new KafkaConsumer<>(
                Map.<String, Object>of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                        bootstrapServers,
                        ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                        false,
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                        "earliest"),
                new StringDeserializer(),
                new ByteArrayDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(Topics.INGEST_CANONICAL).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            List<Message> messages = new ArrayList<>();
            while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                    messages.add(new Message(
                            record.key(),
                            header(record, "eventId"),
                            header(record, "type"),
                            record.partition(),
                            record.offset(),
                            codec.decode(record.value())));
                }
            }
            return messages;
        }
    }

    /** The messages whose event has one of these sourceEventIds, in the order the topic holds them per partition. */
    public static List<Message> forSourceEvents(String bootstrapServers, Set<String> sourceEventIds) {
        return all(bootstrapServers).stream()
                .filter(m -> sourceEventIds.contains(m.event().sourceEventId()))
                .toList();
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
