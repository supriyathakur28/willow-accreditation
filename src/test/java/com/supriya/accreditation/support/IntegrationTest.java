package com.supriya.accreditation.support;

import io.micronaut.test.support.TestPropertyProvider;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
        // As in compose: the topics exist before the service starts, with their real partition counts
        createTopic("accreditation.events.v1", 6);
        createTopic("user.events.v1", 3);
    }

    protected static void createTopic(
            final String name,
            final int partitions) {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(name, partitions, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Could not create topic " + name, e);
        }
    }

    @Override
    public Map<String, String> getProperties() {
        final String mysql = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/";
        return Map.of(
                "datasources.write.url", mysql + "accreditation_write",
                "datasources.read.url", mysql + "accreditation_read",
                "kafka.bootstrap.servers", KAFKA.getBootstrapServers(),
                "redis.uri", "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379) + "?timeout=100ms");
    }

    /** Empties every table in both databases, as root, without going through the application. */
    protected static void resetDatabases() {
        sql("accreditation_write", "SET FOREIGN_KEY_CHECKS = 0",
                "TRUNCATE accreditation_documents", "TRUNCATE accreditations", "TRUNCATE outbox_events",
                "TRUNCATE known_users");
        sql("accreditation_read", "TRUNCATE accreditation_views", "TRUNCATE accreditation_status_history");
    }

    /** What the users consumer does when a record for a new user arrives. */
    protected static void registerUser(final String userId) {
        sql("accreditation_write", "INSERT INTO known_users (user_id, registered_at) VALUES ('" + userId + "', NOW(6))");
    }

    protected static void sql(
            final String database,
            final String... statements) {
        final String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + database;
        try (Connection connection = DriverManager.getConnection(url, "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Rows as column-label maps; handy for asserting on what the application wrote. */
    protected static List<Map<String, Object>> rows(
            final String database,
            final String query) {
        final String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + database;
        try (Connection connection = DriverManager.getConnection(url, "root", MYSQL.getPassword());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(query)) {
            final List<Map<String, Object>> result = new ArrayList<>();
            while (rs.next()) {
                final Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                result.add(row);
            }
            return result;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
