package com.supriya.accreditation.support;

import io.micronaut.test.support.TestPropertyProvider;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.util.Map;

/**
 * Real MySQL, Kafka and Redis in containers, started once and shared by every integration test.
 * Subclasses add {@code @MicronautTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class IntegrationTest implements TestPropertyProvider {

    protected static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            // Same init script as docker compose: two logical databases, one user each
            .withCopyFileToContainer(MountableFile.forHostPath("docker/mysql/init.sql"), "/docker-entrypoint-initdb.d/init.sql")
            .withCommand("--performance-schema=OFF");

    protected static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.1"));

    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    static {
        MYSQL.start();
        KAFKA.start();
        REDIS.start();
    }

    @Override
    public Map<String, String> getProperties() {
        String mysql = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/";
        return Map.of(
                "datasources.write.url", mysql + "accreditation_write",
                "datasources.read.url", mysql + "accreditation_read",
                "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
                "redis.uri", "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }
}
