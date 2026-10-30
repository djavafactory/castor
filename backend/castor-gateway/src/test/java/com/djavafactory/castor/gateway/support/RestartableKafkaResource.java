package com.djavafactory.castor.gateway.support;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A Kafka broker of the test's own that can be stopped and started again on the same address, same as castor-core's.
 * Dev Services brokers are shared by every test of the JVM and must never be stopped, so only a test that needs an
 * outage (RelayOutageIT) uses this. The host port is fixed, otherwise Docker would hand out another one after a restart.
 * The image is the one in compose.yaml and application.properties.
 */
public class RestartableKafkaResource implements QuarkusTestResourceLifecycleManager {

    private static final String IMAGE = "apache/kafka:4.2.2";

    private static KafkaContainer kafka;

    @Override
    public Map<String, String> start() {
        int port = freePort();
        kafka = new KafkaContainer(DockerImageName.parse(IMAGE))
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withPortBindings(new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(9092))));
        kafka.start();
        return Map.of(
                "kafka.bootstrap.servers", kafka.getBootstrapServers(), "quarkus.kafka.devservices.enabled", "false");
    }

    @Override
    public void stop() {
        if (kafka != null) {
            kafka.stop();
            kafka = null;
        }
    }

    /** Stops the broker process; the container and its data stay. */
    public static void stopBroker() {
        kafka.getDockerClient().stopContainerCmd(kafka.getContainerId()).exec();
    }

    /** Starts the same container again, on the same address and with the same data. */
    public static void startBroker() {
        kafka.getDockerClient().startContainerCmd(kafka.getContainerId()).exec();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("No free port for the test broker", e);
        }
    }
}
