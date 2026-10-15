package com.supriya.accreditation.query;

import com.supriya.accreditation.platform.messaging.Topics;
import com.supriya.accreditation.support.ApiIntegrationTest;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The whole asynchronous path: admin write → outbox → Kafka → consumers → client GET and history.
 * Only this class turns on the relay and the consumers.
 */
@MicronautTest(transactional = false)
@Property(name = "outbox.relay.enabled", value = "true")
@Property(name = "outbox.relay.interval", value = "100ms")
@Property(name = "messaging.consumers.enabled", value = "true")
class EventFlowIntegrationTest extends ApiIntegrationTest {

    private static final Duration EVENTUALLY = Duration.ofSeconds(30);
    private static final KafkaProducer<String, String> PRODUCER = new KafkaProducer<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));

    @BeforeEach
    void cleanState() throws Exception {
        resetDatabases();
        REDIS.execInContainer("redis-cli", "FLUSHALL");
        registerUser(USER);
    }

    @AfterAll
    void closeProducer() {
        PRODUCER.close();
    }

    @Test
    void theClientSeesEveryChangeAndTheHistoryRecordsIt() {
        String id = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST)));
        await().atMost(EVENTUALLY).until(() -> statusOf(id), "PENDING"::equals);

        send(put(id, "CONFIRMED"));
        await().atMost(EVENTUALLY).until(() -> statusOf(id), "CONFIRMED"::equals);

        send(put(id, "FAILED")); // revocation
        await().atMost(EVENTUALLY).until(() -> statusOf(id), "FAILED"::equals);

        Reply get = send(HttpRequest.GET("/user/" + USER + "/accreditation"));
        assertThat(get.body()).isEqualTo(Map.of("user_id", USER, "accreditation_statuses",
                Map.of(id, Map.of("accreditation_type", "BY_INCOME", "status", "FAILED"))));

        List<Map<String, Object>> history = await().atMost(EVENTUALLY)
                .until(() -> historyOf(id), h -> h.size() == 3);
        assertThat(history).extracting(h -> h.get("version")).containsExactly(1, 2, 3);
        assertThat(history).extracting(h -> h.get("event_type"))
                .containsExactly("AccreditationSubmitted", "AccreditationConfirmed", "AccreditationFailed");
        assertThat(history.get(0)).containsEntry("from_status", null).containsEntry("to_status", "PENDING");
        assertThat(history.get(2)).containsEntry("from_status", "CONFIRMED").containsEntry("to_status", "FAILED")
                .containsEntry("performed_by", "anonymous");
        assertThat(rows("accreditation_write", "SELECT * FROM outbox_events WHERE published_at IS NULL")).isEmpty();
    }

    @Test
    void anUnknownUserHasAnEmptyMapAndAMalformedOneIsRejected() {
        Reply unknown = send(HttpRequest.GET("/user/nobody/accreditation"));
        Reply malformed = send(HttpRequest.GET("/user/not%20valid/accreditation"));

        assertThat(unknown.status()).isEqualTo(200);
        assertThat(unknown.body()).isEqualTo(Map.of("user_id", "nobody", "accreditation_statuses", Map.of()));
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(invalidFields(malformed)).containsExactly("user_id:INVALID_VALUE");
    }

    @Test
    void historyOfAnUnknownAccreditationIsNotFound() {
        assertThat(send(HttpRequest.GET("/user/accreditation/" + UUID.randomUUID() + "/history")).status()).isEqualTo(404);
        assertThat(send(HttpRequest.GET("/user/accreditation/nope/history")).status()).isEqualTo(404);
    }

    @Test
    void duplicateAndLateEventsNeverMoveTheViewBackwards() {
        UUID id = UUID.randomUUID();
        String v1 = event(UUID.randomUUID(), id, "AccreditationSubmitted", null, "PENDING", 1);
        String v2 = event(UUID.randomUUID(), id, "AccreditationConfirmed", "PENDING", "CONFIRMED", 2);
        String v3 = event(UUID.randomUUID(), id, "AccreditationExpired", "CONFIRMED", "EXPIRED", 3);

        publish(id, v3);
        publish(id, v1); // late
        publish(id, v3); // duplicate
        publish(id, v2); // late

        await().atMost(EVENTUALLY).until(() -> historyOf(id.toString()), h -> h.size() == 3);
        assertThat(statusOf(id.toString())).isEqualTo("EXPIRED");
        assertThat(rows("accreditation_read", "SELECT version FROM accreditation_views").getFirst())
                .containsEntry("version", 3L);
    }

    @Test
    void poisonEventsGoToEachGroupsDeadLetterTopicAndTheStreamContinues() {
        UUID id = UUID.randomUUID();
        String marker = "poison-" + UUID.randomUUID();
        publish(id, "{\"not\": \"an event\", \"marker\": \"" + marker + "\"}");
        publish(id, event(UUID.randomUUID(), id, "AccreditationSubmitted", null, "PENDING", 1));

        await().atMost(EVENTUALLY).until(() -> statusOf(id.toString()), "PENDING"::equals);
        for (String group : List.of("accreditation-projection", "accreditation-history")) {
            List<ConsumerRecord<String, String>> dead = await().atMost(EVENTUALLY).until(
                    () -> deadLetters(Topics.deadLetter(Topics.ACCREDITATION_EVENTS, group), marker), d -> !d.isEmpty());
            assertThat(header(dead.getFirst(), "dlt.error")).contains("PoisonRecordException");
        }
    }

    @Test
    void aSecondEventForTheSameVersionIsABugForHistoryOnly() {
        UUID id = UUID.randomUUID();
        UUID conflicting = UUID.randomUUID();
        publish(id, event(UUID.randomUUID(), id, "AccreditationSubmitted", null, "PENDING", 1));
        publish(id, event(conflicting, id, "AccreditationSubmitted", null, "PENDING", 1));

        String historyDlt = Topics.deadLetter(Topics.ACCREDITATION_EVENTS, "accreditation-history");
        List<ConsumerRecord<String, String>> dead = await().atMost(EVENTUALLY)
                .until(() -> deadLetters(historyDlt, conflicting.toString()), d -> !d.isEmpty());
        assertThat(header(dead.getFirst(), "dlt.error")).contains("already recorded under a different event");
        assertThat(deadLetters(Topics.deadLetter(Topics.ACCREDITATION_EVENTS, "accreditation-projection"),
                conflicting.toString())).isEmpty();
    }

    @Test
    void aUserRegisteredUpstreamCanSubmit() {
        String newUser = "u" + UUID.randomUUID().toString().replace("-", "");
        PRODUCER.send(new ProducerRecord<>(Topics.USER_EVENTS, newUser, """
                {"event_id": "e-1", "event_type": "UserRegistered", "schema_version": 1,
                 "user_id": "%s", "registered_at": "2026-10-01T09:00:00Z"}""".formatted(newUser)));
        PRODUCER.flush();

        await().atMost(EVENTUALLY).until(() ->
                send(HttpRequest.POST("/user/accreditation", BRIEF_POST.replace(USER, newUser))).status() == 201);
        await().atMost(EVENTUALLY).until(() -> !rows("accreditation_read",
                "SELECT * FROM known_users WHERE user_id = '" + newUser + "'").isEmpty());
    }

    @Test
    void eventsWrittenWhileKafkaIsDownArePublishedWhenItReturns() {
        var docker = KAFKA.getDockerClient();
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        String id;
        try {
            // The admin API does not need Kafka: the event waits in the outbox
            id = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST)));
            assertThat(rows("accreditation_write", "SELECT * FROM outbox_events WHERE published_at IS NULL")).hasSize(1);
        } finally {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(60)).until(() -> statusOf(id), "PENDING"::equals);
    }

    private String statusOf(String id) {
        Reply reply = send(HttpRequest.GET("/user/" + USER + "/accreditation"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> statuses = (Map<String, Map<String, Object>>) reply.body().get("accreditation_statuses");
        if (statuses == null || !statuses.containsKey(id)) {
            return null;
        }
        return (String) statuses.get(id).get("status");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> historyOf(String id) {
        Reply reply = send(HttpRequest.GET("/user/accreditation/" + id + "/history"));
        return reply.status() == 200 ? (List<Map<String, Object>>) reply.body().get("history") : List.of();
    }

    private static String event(UUID eventId, UUID accreditationId, String type, String previous, String status, int version) {
        return """
                {"event_id": "%s", "event_type": "%s", "schema_version": 1, "accreditation_id": "%s",
                 "user_id": "%s", "accreditation_type": "BY_INCOME", %s "status": "%s", "version": %d,
                 "submitted_at": "2026-10-01T09:00:00Z", "occurred_at": "2026-10-0%dT09:00:00Z", "performed_by": "admin"}"""
                .formatted(eventId, type, accreditationId, USER,
                        previous == null ? "" : "\"previous_status\": \"" + previous + "\",", status, version, version);
    }

    private static void publish(UUID key, String value) {
        PRODUCER.send(new ProducerRecord<>(Topics.ACCREDITATION_EVENTS, key.toString(), value));
        PRODUCER.flush();
    }

    private static List<ConsumerRecord<String, String>> deadLetters(String topic, String containing) {
        List<ConsumerRecord<String, String>> result = new ArrayList<>();
        try (KafkaConsumer<String, String> reader = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            reader.subscribe(List.of(topic));
            for (int i = 0; i < 4; i++) {
                reader.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (r.value() != null && r.value().contains(containing)) {
                        result.add(r);
                    }
                });
            }
        }
        return result;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
