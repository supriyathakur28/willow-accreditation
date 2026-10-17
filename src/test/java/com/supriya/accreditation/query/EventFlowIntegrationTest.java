package com.supriya.accreditation.query;

import com.supriya.accreditation.command.ExpiryJob;
import com.supriya.accreditation.platform.messaging.Topics;
import com.supriya.accreditation.support.ApiIntegrationTest;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
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
@Property(name = "expiry.enabled", value = "true")
@Property(name = "expiry.initial-delay", value = "1h") // run by the test, not the scheduler
class EventFlowIntegrationTest extends ApiIntegrationTest {

    @Inject ExpiryJob expiryJob;

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
        final String id = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST)));
        await().atMost(EVENTUALLY).until(() -> statusOf(id), "PENDING"::equals);

        send(put(id, "CONFIRMED"));
        await().atMost(EVENTUALLY).until(() -> statusOf(id), "CONFIRMED"::equals);

        send(put(id, "FAILED")); // revocation
        await().atMost(EVENTUALLY).until(() -> statusOf(id), "FAILED"::equals);

        final Reply get = send(HttpRequest.GET("/user/" + USER + "/accreditation"));
        assertThat(get.body()).isEqualTo(Map.of("user_id", USER, "accreditation_statuses",
                Map.of(id, Map.of("accreditation_type", "BY_INCOME", "status", "FAILED"))));

        final List<Map<String, Object>> history = await().atMost(EVENTUALLY)
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
    void anExpiryByTheJobReachesTheClientAndTheHistory() {
        final String id = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST)));
        send(put(id, "CONFIRMED"));
        // Thirty-one days pass
        sql("accreditation_write", "UPDATE accreditations SET updated_at = NOW(6) - INTERVAL 31 DAY");

        assertThat(expiryJob.expireStaleConfirmations().expired()).isEqualTo(1);

        await().atMost(EVENTUALLY).until(() -> statusOf(id), "EXPIRED"::equals);
        final List<Map<String, Object>> history = await().atMost(EVENTUALLY).until(() -> historyOf(id), h -> h.size() == 3);
        assertThat(history.get(2)).containsEntry("event_type", "AccreditationExpired")
                .containsEntry("from_status", "CONFIRMED").containsEntry("performed_by", "system:expiry-job");
    }

    @Test
    void anUnknownUserHasAnEmptyMapAndAMalformedOneIsRejected() {
        final Reply unknown = send(HttpRequest.GET("/user/nobody/accreditation"));
        final Reply malformed = send(HttpRequest.GET("/user/not%20valid/accreditation"));

        assertThat(unknown.status()).isEqualTo(200);
        assertThat(unknown.body()).isEqualTo(Map.of("user_id", "nobody", "accreditation_statuses", Map.of()));
        assertThat(malformed.status()).isEqualTo(400);
        assertThat(invalidFields(malformed)).containsExactly("user_id:INVALID_VALUE");
    }

    @Test
    void eachUserSeesOnlyTheirOwnAccreditations() {
        final String otherUser = USER.toUpperCase(); // user IDs are case-sensitive, so this is someone else
        registerUser(otherUser);
        final String own = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST)));
        final String others = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST.replace(USER, otherUser))));

        await().atMost(EVENTUALLY).until(() -> accreditationsOf(USER).containsKey(own)
                && accreditationsOf(otherUser).containsKey(others));

        assertThat(accreditationsOf(USER)).containsOnlyKeys(own);
        assertThat(accreditationsOf(otherUser)).containsOnlyKeys(others);
    }

    @Test
    void historyOfAnUnknownAccreditationIsNotFound() {
        assertThat(send(HttpRequest.GET("/user/accreditation/" + UUID.randomUUID() + "/history")).status()).isEqualTo(404);
        assertThat(send(HttpRequest.GET("/user/accreditation/nope/history")).status()).isEqualTo(404);
    }

    @Test
    void duplicateAndLateEventsNeverMoveTheViewBackwards() {
        final UUID id = UUID.randomUUID();
        final String v1 = event(UUID.randomUUID(), id, "AccreditationSubmitted", null, "PENDING", 1);
        final String v2 = event(UUID.randomUUID(), id, "AccreditationConfirmed", "PENDING", "CONFIRMED", 2);
        final String v3 = event(UUID.randomUUID(), id, "AccreditationExpired", "CONFIRMED", "EXPIRED", 3);

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
        final UUID id = UUID.randomUUID();
        final String marker = "poison-" + UUID.randomUUID();
        publish(id, "{\"not\": \"an event\", \"marker\": \"" + marker + "\"}");
        publish(id, event(UUID.randomUUID(), id, "AccreditationSubmitted", null, "PENDING", 1));

        await().atMost(EVENTUALLY).until(() -> statusOf(id.toString()), "PENDING"::equals);
        for (String group : List.of("accreditation-projection", "accreditation-history")) {
            final List<ConsumerRecord<String, String>> dead = await().atMost(EVENTUALLY).until(
                    () -> deadLetters(Topics.deadLetter(Topics.ACCREDITATION_EVENTS, group), marker), d -> !d.isEmpty());
            assertThat(header(dead.getFirst(), "dlt.error")).contains("PoisonRecordException");
        }
    }

    @Test
    void aSecondEventForTheSameVersionIsABugForHistoryOnly() {
        final UUID id = UUID.randomUUID();
        final UUID conflicting = UUID.randomUUID();
        publish(id, event(UUID.randomUUID(), id, "AccreditationSubmitted", null, "PENDING", 1));
        publish(id, event(conflicting, id, "AccreditationSubmitted", null, "PENDING", 1));

        final String historyDlt = Topics.deadLetter(Topics.ACCREDITATION_EVENTS, "accreditation-history");
        final List<ConsumerRecord<String, String>> dead = await().atMost(EVENTUALLY)
                .until(() -> deadLetters(historyDlt, conflicting.toString()), d -> !d.isEmpty());
        assertThat(header(dead.getFirst(), "dlt.error")).contains("already recorded under a different event");
        assertThat(deadLetters(Topics.deadLetter(Topics.ACCREDITATION_EVENTS, "accreditation-projection"),
                conflicting.toString())).isEmpty();
    }

    @Test
    void aUserRegisteredUpstreamCanSubmit() {
        final String newUser = "u" + UUID.randomUUID().toString().replace("-", "");
        PRODUCER.send(new ProducerRecord<>(Topics.USER_EVENTS, newUser, """
                {"event_id": "e-1", "event_type": "UserRegistered", "schema_version": 1,
                 "user_id": "%s", "registered_at": "2026-10-01T09:00:00Z"}""".formatted(newUser)));
        PRODUCER.flush();

        await().atMost(EVENTUALLY).until(() ->
                send(HttpRequest.POST("/user/accreditation", BRIEF_POST.replace(USER, newUser))).status() == 201);
        assertThat(rows("accreditation_write", "SELECT registered_at FROM known_users WHERE user_id = '" + newUser + "'"))
                .singleElement().satisfies(row -> assertThat(row.get("registered_at").toString()).startsWith("2026-10-01"));
    }

    @Test
    void anyRecordForAUserMeansTheUserExists() {
        // On a compacted topic the latest record may be of any type: here a snapshot with no registration time
        final String newUser = "u" + UUID.randomUUID().toString().replace("-", "");
        PRODUCER.send(new ProducerRecord<>(Topics.USER_EVENTS, newUser, """
                {"event_type": "UserProfileUpdated", "user_id": "%s", "status": "ACTIVE"}""".formatted(newUser)));
        PRODUCER.flush();

        await().atMost(EVENTUALLY).until(() ->
                send(HttpRequest.POST("/user/accreditation", BRIEF_POST.replace(USER, newUser))).status() == 201);
    }

    @Test
    void aTombstoneIsAcceptedAndAMismatchedKeyIsPoison() {
        final String deleted = "u" + UUID.randomUUID().toString().replace("-", "");
        final String mismatched = "u" + UUID.randomUUID().toString().replace("-", "");
        final String next = "u" + UUID.randomUUID().toString().replace("-", "");
        PRODUCER.send(new ProducerRecord<>(Topics.USER_EVENTS, deleted, null));
        PRODUCER.send(new ProducerRecord<>(Topics.USER_EVENTS, mismatched, "{\"user_id\": \"someone-else\"}"));
        PRODUCER.send(new ProducerRecord<>(Topics.USER_EVENTS, next, "{\"user_id\": \"%s\"}".formatted(next)));
        PRODUCER.flush();

        // The stream moves past both: a user published later on the same topic still arrives
        await().atMost(EVENTUALLY).until(() ->
                send(HttpRequest.POST("/user/accreditation", BRIEF_POST.replace(USER, next))).status() == 201);
        final List<ConsumerRecord<String, String>> dead = await().atMost(EVENTUALLY).until(
                () -> deadLetters(Topics.deadLetter(Topics.USER_EVENTS, "known-users"), "someone-else"), d -> !d.isEmpty());
        assertThat(header(dead.getFirst(), "dlt.error")).contains("does not match its user_id");
        assertThat(rows("accreditation_write", "SELECT * FROM known_users WHERE user_id IN ('" + deleted + "', '"
                + mismatched + "')")).isEmpty();
    }

    @Test
    void eventsWrittenWhileKafkaIsDownArePublishedWhenItReturns() {
        final var docker = KAFKA.getDockerClient();
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

    private String statusOf(final String id) {
        final Map<String, Object> accreditation = accreditationsOf(USER).get(id);
        return accreditation == null ? null : (String) accreditation.get("status");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> accreditationsOf(final String userId) {
        final Reply reply = send(HttpRequest.GET("/user/" + userId + "/accreditation"));
        final Map<String, Map<String, Object>> statuses =
                (Map<String, Map<String, Object>>) reply.body().get("accreditation_statuses");
        return statuses == null ? Map.of() : statuses;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> historyOf(String id) {
        final Reply reply = send(HttpRequest.GET("/user/accreditation/" + id + "/history"));
        return reply.status() == 200 ? (List<Map<String, Object>>) reply.body().get("history") : List.of();
    }

    private static String event(
            final UUID eventId,
            final UUID accreditationId,
            final String type,
            final String previous,
            final String status,
            final int version) {
        return """
                {"event_id": "%s", "event_type": "%s", "schema_version": 1, "accreditation_id": "%s",
                 "user_id": "%s", "accreditation_type": "BY_INCOME", %s "status": "%s", "version": %d,
                 "submitted_at": "2026-10-01T09:00:00Z", "occurred_at": "2026-10-0%dT09:00:00Z", "performed_by": "admin"}"""
                .formatted(eventId, type, accreditationId, USER,
                        previous == null ? "" : "\"previous_status\": \"" + previous + "\",", status, version, version);
    }

    private static void publish(
            final UUID key,
            final String value) {
        PRODUCER.send(new ProducerRecord<>(Topics.ACCREDITATION_EVENTS, key.toString(), value));
        PRODUCER.flush();
    }

    private static List<ConsumerRecord<String, String>> deadLetters(
            final String topic,
            final String containing) {
        final List<ConsumerRecord<String, String>> result = new ArrayList<>();
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

    private static String header(
            final ConsumerRecord<String, String> record,
            final String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
