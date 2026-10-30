package com.djavafactory.castor.core.operations.infrastructure;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.djavafactory.castor.core.operations.application.EventSender;

/**
 * Sends with an idempotent producer and {@code acks=all} and waits for the broker's answer. The producer is bounded by
 * a send timeout, so a broker that is away costs one timeout, not a hung thread. After a failure the producer is closed
 * and built again on the next send.
 */
@ApplicationScoped
public class KafkaEventSender implements EventSender {

    private static final Logger LOG = Logger.getLogger(KafkaEventSender.class);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final String bootstrapServers;
    private Producer<String, byte[]> producer;

    KafkaEventSender(@ConfigProperty(name = "kafka.bootstrap.servers") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    @Override
    public synchronized void send(String topic, String key, byte[] value, Map<String, String> headers) {
        var record = new ProducerRecord<>(topic, key, value);
        headers.forEach((name, text) -> record.headers().add(name, text.getBytes(StandardCharsets.UTF_8)));
        try {
            producer().send(record).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeProducer();
            throw new IllegalStateException("Interrupted while sending to " + topic, e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            closeProducer();
            throw new IllegalStateException("Sending to " + topic + " failed", e);
        }
    }

    private Producer<String, byte[]> producer() {
        if (producer == null) {
            int timeout = (int) SEND_TIMEOUT.toMillis();
            producer = new KafkaProducer<>(
                    Map.<String, Object>of(
                            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                            bootstrapServers,
                            ProducerConfig.CLIENT_ID_CONFIG,
                            "castor-core-projector",
                            ProducerConfig.ACKS_CONFIG,
                            "all",
                            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                            true,
                            ProducerConfig.MAX_BLOCK_MS_CONFIG,
                            timeout,
                            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                            timeout,
                            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                            timeout / 2,
                            ProducerConfig.LINGER_MS_CONFIG,
                            0),
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
    synchronized void close() {
        closeProducer();
    }
}
