package com.supriya.accreditation.platform.messaging;

import com.supriya.accreditation.support.IntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** The consumer loop against a real broker: retry in place keeps order, poison is set aside, bugs cannot stall. */
class RetryingConsumerTest extends IntegrationTest {

    private String topic;
    private Producer<String, String> producer;
    private final List<Thread> threads = new ArrayList<>();
    private final List<RetryingConsumer> consumers = new ArrayList<>();

    @BeforeEach
    void freshTopic() {
        topic = "test-" + UUID.randomUUID();
        createTopic(topic, 1);
        producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }

    @AfterEach
    void stop() throws InterruptedException {
        consumers.forEach(RetryingConsumer::stop);
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        producer.close();
    }

    @Test
    void retriesAnInfrastructureFailureInPlaceAndKeepsOrder() {
        final List<String> applied = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresLeft = new AtomicInteger(3);
        send("a", "b", "c");

        start("in-place", record -> {
            if (record.value().equals("b") && failuresLeft.getAndDecrement() > 0) {
                throw new SQLTransientConnectionException("database unavailable");
            }
            applied.add(record.value());
        });

        await().atMost(Duration.ofSeconds(20)).until(() -> applied.size() == 3);
        assertThat(applied).containsExactly("a", "b", "c"); // c never overtook b
        assertThat(deadLetters("in-place")).isEmpty();
        await().untilAsserted(() -> assertThat(committedOffset("in-place")).isEqualTo(3));
    }

    @Test
    void sendsAPoisonRecordToTheGroupsDeadLetterTopicAndMovesOn() {
        final List<String> applied = new CopyOnWriteArrayList<>();
        send("a", "poison", "c");

        start("poison", record -> {
            if (record.value().equals("poison")) {
                throw new PoisonRecordException("cannot parse");
            }
            applied.add(record.value());
        });

        await().atMost(Duration.ofSeconds(20)).until(() -> applied.size() == 2);
        assertThat(applied).containsExactly("a", "c");
        final List<ConsumerRecord<String, String>> dead = await().atMost(Duration.ofSeconds(20))
                .until(() -> deadLetters("poison"), d -> d.size() == 1);
        assertThat(dead.getFirst().value()).isEqualTo("poison");
        assertThat(header(dead.getFirst(), "dlt.error")).contains("PoisonRecordException: cannot parse");
        assertThat(header(dead.getFirst(), "dlt.original.offset")).isEqualTo("1");
        assertThat(header(dead.getFirst(), "dlt.consumer.group")).isEqualTo("poison");
    }

    @Test
    void givesUpOnAnUnrecognisedErrorAfterFiveAttempts() {
        final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
        final List<String> applied = new CopyOnWriteArrayList<>();
        send("bug", "next");

        start("bounded", record -> {
            attempts.computeIfAbsent(record.value(), v -> new AtomicInteger()).incrementAndGet();
            if (record.value().equals("bug")) {
                throw new IllegalStateException("a bug");
            }
            applied.add(record.value());
        });

        await().atMost(Duration.ofSeconds(20)).until(() -> applied.contains("next"));
        assertThat(attempts.get("bug")).hasValue(5);
        assertThat(deadLetters("bounded")).singleElement().satisfies(r -> assertThat(r.value()).isEqualTo("bug"));
    }

    @Test
    void backsOffExponentiallyUpToTheCap() {
        final RetryingConsumer consumer = new RetryingConsumer(RetryingConsumer.Settings.defaults("g", "t"), null, null,
                record -> {
                }, Clock.systemUTC());

        assertThat(List.of(1, 2, 3, 4, 6, 7, 50)).extracting(consumer::backoff).containsExactly(
                Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4),
                Duration.ofSeconds(16), Duration.ofSeconds(30), Duration.ofSeconds(30));
    }

    private void start(
            final String group,
            final RecordHandler handler) {
        final var settings = new RetryingConsumer.Settings(group, topic, Topics.deadLetter(topic, group),
                Duration.ofMillis(100), Duration.ofMillis(50), Duration.ofMillis(200), 5, Duration.ofSeconds(60));
        final RetryingConsumer consumer = new RetryingConsumer(settings, newConsumer(group), producer, handler, Clock.systemUTC());
        consumers.add(consumer);
        threads.add(Thread.ofPlatform().start(consumer));
    }

    private void send(final String... values) {
        for (String value : values) {
            producer.send(new ProducerRecord<>(topic, "key", value));
        }
        producer.flush();
    }

    private KafkaConsumer<String, String> newConsumer(final String group) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, group + "-" + topic,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }

    private List<ConsumerRecord<String, String>> deadLetters(final String group) {
        final List<ConsumerRecord<String, String>> result = new ArrayList<>();
        try (KafkaConsumer<String, String> reader = newConsumer("reader-" + UUID.randomUUID())) {
            reader.subscribe(List.of(Topics.deadLetter(topic, group)));
            for (int i = 0; i < 5; i++) {
                reader.poll(Duration.ofMillis(500)).forEach(result::add);
            }
        }
        return result;
    }

    private long committedOffset(final String group) {
        try (KafkaConsumer<String, String> reader = newConsumer(group)) {
            final var partition = new org.apache.kafka.common.TopicPartition(topic, 0);
            final var committed = reader.committed(java.util.Set.of(partition)).get(partition);
            return committed == null ? -1 : committed.offset();
        }
    }

    private static String header(
            final ConsumerRecord<String, String> record,
            final String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
