package com.djavafactory.castor.gateway.relay.infrastructure;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.djavafactory.castor.contracts.Topics;
import com.djavafactory.castor.gateway.relay.domain.EventPublisher;
import com.djavafactory.castor.gateway.relay.domain.OutboxMessage;
import com.djavafactory.castor.gateway.shared.infrastructure.CastorConfig;
import com.djavafactory.castor.gateway.shared.infrastructure.KafkaTopics;

/**
 * Publishes to {@link Topics#INGEST_CANONICAL} with an idempotent producer and {@code acks=all}. The broker's
 * acknowledgement is awaited for every message before the caller marks anything published. The producer is bounded by
 * the send timeout (max.block.ms and delivery.timeout.ms), so a broker that is away costs one timeout per relay run, not
 * a hung thread. After any failure the producer is closed and built again on the next run.
 */
@ApplicationScoped
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger LOG = Logger.getLogger(KafkaEventPublisher.class);

    private final String bootstrapServers;
    private final Duration sendTimeout;
    private final KafkaTopics topics;
    private Producer<String, byte[]> producer;

    public KafkaEventPublisher(
            @ConfigProperty(name = "kafka.bootstrap.servers") String bootstrapServers,
            CastorConfig castor,
            KafkaTopics topics) {
        this.bootstrapServers = bootstrapServers;
        this.sendTimeout = castor.relay().sendTimeout();
        this.topics = topics;
    }

    @Override
    public synchronized List<Boolean> publish(List<OutboxMessage> messages) {
        boolean[] acknowledged = new boolean[messages.size()];
        try {
            topics.ensure(sendTimeout);
            send(messages, acknowledged);
        } catch (RuntimeException e) {
            LOG.debugf(e, "Publishing failed at %s", bootstrapServers);
            closeProducer();
        }
        List<Boolean> result = new ArrayList<>(acknowledged.length);
        for (boolean ack : acknowledged) {
            result.add(ack);
        }
        return result;
    }

    private void send(List<OutboxMessage> messages, boolean[] acknowledged) {
        Producer<String, byte[]> kafka = producer();
        List<Future<RecordMetadata>> pending = new ArrayList<>(messages.size());
        for (OutboxMessage message : messages) {
            var record = new ProducerRecord<>(Topics.INGEST_CANONICAL, message.aggregateId(), message.payload());
            record.headers()
                    .add("eventId", message.eventId().getBytes(StandardCharsets.UTF_8))
                    .add("type", message.type().getBytes(StandardCharsets.UTF_8));
            try {
                pending.add(kafka.send(record));
            } catch (RuntimeException e) {
                // Nothing after this message may be sent, or an aggregate's events could overtake each other
                LOG.debugf(e, "Send of %s failed", message.eventId());
                break;
            }
        }
        try {
            kafka.flush();
        } catch (RuntimeException e) {
            LOG.debugf(e, "Flush failed");
        }
        boolean failed = pending.size() < messages.size();
        for (int i = 0; i < pending.size(); i++) {
            try {
                pending.get(i).get();
                acknowledged[i] = true;
            } catch (ExecutionException e) {
                failed = true;
                LOG.debugf(e.getCause(), "Broker refused %s", messages.get(i).eventId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed = true;
                break;
            }
        }
        if (failed) {
            closeProducer();
        }
    }

    private Producer<String, byte[]> producer() {
        if (producer == null) {
            long timeout = sendTimeout.toMillis();
            producer = new KafkaProducer<>(
                    Map.<String, Object>of(
                            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                            bootstrapServers,
                            ProducerConfig.CLIENT_ID_CONFIG,
                            "castor-gateway-relay",
                            ProducerConfig.ACKS_CONFIG,
                            "all",
                            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                            true,
                            ProducerConfig.MAX_BLOCK_MS_CONFIG,
                            timeout,
                            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                            (int) timeout,
                            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                            (int) Math.min(5_000, timeout / 2),
                            ProducerConfig.LINGER_MS_CONFIG,
                            5),
                    new StringSerializer(),
                    new ByteArraySerializer());
        }
        return producer;
    }

    private void closeProducer() {
        if (producer != null) {
            try {
                producer.close(Duration.ofSeconds(1));
            } catch (RuntimeException e) {
                LOG.debug("Closing the producer failed", e);
            }
            producer = null;
        }
    }

    @PreDestroy
    public synchronized void close() {
        closeProducer();
    }
}
