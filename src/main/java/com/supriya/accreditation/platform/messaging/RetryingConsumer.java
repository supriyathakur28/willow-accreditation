package com.supriya.accreditation.platform.messaging;

import com.supriya.accreditation.platform.persistence.TransientErrors;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * One consumer-group member: polls, applies records in order, commits offsets only for applied records.
 * <ul>
 *   <li>Infrastructure failure (database down, lock timeout): retried in place, indefinitely. The consumer seeks back
 *       to the failed record and pauses that partition for the backoff (0.5 s doubling to 30 s), so it keeps polling,
 *       stays in the group, and preserves per-key order.</li>
 *   <li>Poison record: sent to this group's dead-letter topic with the error in headers; the partition moves on.</li>
 *   <li>Unrecognised error: a bounded number of attempts, then the dead-letter topic, so a bug never stalls a
 *       partition forever.</li>
 * </ul>
 * Not thread-safe, like {@link Consumer}: one instance runs on one thread.
 */
public final class RetryingConsumer implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(RetryingConsumer.class);

    public record Settings(String groupId, String topic, String deadLetterTopic, Duration pollTimeout,
                           Duration initialBackoff, Duration maxBackoff, int maxAttemptsForUnknownErrors,
                           Duration stuckAlertAfter) {

        public static Settings defaults(String groupId, String topic) {
            return new Settings(groupId, topic, Topics.deadLetter(topic, groupId), Duration.ofMillis(500),
                    Duration.ofMillis(500), Duration.ofSeconds(30), 5, Duration.ofSeconds(60));
        }
    }

    /** The record a partition is stuck on, and when to try it again. */
    private record Retry(long offset, int attempts, Instant firstFailureAt, Instant nextAttemptAt, boolean alerted) {
    }

    private final Settings settings;
    private final Consumer<String, String> consumer;
    private final Producer<String, String> deadLetters;
    private final RecordHandler handler;
    private final Clock clock;
    private final Map<TopicPartition, Retry> retries = new HashMap<>();
    private volatile boolean running = true;

    public RetryingConsumer(Settings settings, Consumer<String, String> consumer, Producer<String, String> deadLetters,
                            RecordHandler handler, Clock clock) {
        this.settings = settings;
        this.consumer = consumer;
        this.deadLetters = deadLetters;
        this.handler = handler;
        this.clock = clock;
    }

    @Override
    public void run() {
        subscribe();
        try {
            while (running) {
                pollOnce();
            }
        } catch (WakeupException e) {
            if (running) {
                throw e;
            }
        } finally {
            consumer.close(Duration.ofSeconds(5));
            log.info("Consumer {} stopped", settings.groupId());
        }
    }

    /** Safe to call from another thread. */
    public void stop() {
        running = false;
        consumer.wakeup();
    }

    void subscribe() {
        consumer.subscribe(List.of(settings.topic()), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                partitions.forEach(retries::remove);
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                // Offsets were committed record by record, so a new owner starts after the last applied record
            }
        });
    }

    void pollOnce() {
        resumeDuePartitions();
        ConsumerRecords<String, String> records = consumer.poll(settings.pollTimeout());
        Map<TopicPartition, OffsetAndMetadata> applied = new HashMap<>();
        for (TopicPartition partition : records.partitions()) {
            for (ConsumerRecord<String, String> record : records.records(partition)) {
                if (!process(partition, record)) {
                    break; // paused; this record and the ones after it are fetched again on resume
                }
                applied.put(partition, new OffsetAndMetadata(record.offset() + 1));
            }
        }
        if (!applied.isEmpty()) {
            consumer.commitSync(applied);
        }
    }

    private boolean process(TopicPartition partition, ConsumerRecord<String, String> record) {
        try (var ignored = MDC.putCloseable("requestId", header(record, "X-Request-Id"))) {
            try {
                handler.handle(record);
                clearRetry(partition);
                return true;
            } catch (PoisonRecordException e) {
                log.warn("Poison record {}-{}@{}: {}", record.topic(), record.partition(), record.offset(), e.getMessage());
                return deadLetter(partition, record, e);
            } catch (Exception e) {
                Retry retry = recordFailure(partition, record);
                if (!TransientErrors.isTransient(e) && retry.attempts() >= settings.maxAttemptsForUnknownErrors()) {
                    log.error("Giving up on {}-{}@{} after {} attempts", record.topic(), record.partition(),
                            record.offset(), retry.attempts(), e);
                    return deadLetter(partition, record, e);
                }
                log.warn("Failed {}-{}@{} (attempt {}), retrying in place: {}", record.topic(), record.partition(),
                        record.offset(), retry.attempts(), e.toString());
                pauseUntilRetry(partition, record);
                return false;
            }
        }
    }

    private Retry recordFailure(TopicPartition partition, ConsumerRecord<String, String> record) {
        Instant now = clock.instant();
        Retry previous = retries.get(partition);
        boolean sameRecord = previous != null && previous.offset() == record.offset();
        int attempts = sameRecord ? previous.attempts() + 1 : 1;
        Instant firstFailureAt = sameRecord ? previous.firstFailureAt() : now;
        boolean alerted = sameRecord && previous.alerted();
        if (!alerted && Duration.between(firstFailureAt, now).compareTo(settings.stuckAlertAfter()) >= 0) {
            // The alerting hook: a partition has made no progress for a minute
            log.error("ALERT consumer {} stuck on {}-{}@{} since {}", settings.groupId(), record.topic(),
                    record.partition(), record.offset(), firstFailureAt);
            alerted = true;
        }
        Retry retry = new Retry(record.offset(), attempts, firstFailureAt, now.plus(backoff(attempts)), alerted);
        retries.put(partition, retry);
        return retry;
    }

    Duration backoff(int attempts) {
        long millis = settings.initialBackoff().toMillis() << Math.min(attempts - 1, 20);
        return Duration.ofMillis(Math.min(millis, settings.maxBackoff().toMillis()));
    }

    private void pauseUntilRetry(TopicPartition partition, ConsumerRecord<String, String> record) {
        consumer.seek(partition, record.offset());
        consumer.pause(Set.of(partition));
    }

    private void resumeDuePartitions() {
        Instant now = clock.instant();
        Set<TopicPartition> paused = consumer.paused();
        retries.forEach((partition, retry) -> {
            if (paused.contains(partition) && !retry.nextAttemptAt().isAfter(now)) {
                consumer.resume(Set.of(partition));
            }
        });
    }

    private void clearRetry(TopicPartition partition) {
        Retry cleared = retries.remove(partition);
        if (cleared != null && cleared.alerted()) {
            log.info("Consumer {} recovered on {}", settings.groupId(), partition);
        }
    }

    /** Returns true when the record is safely in the dead-letter topic; false means "retry it later". */
    private boolean deadLetter(TopicPartition partition, ConsumerRecord<String, String> record, Exception error) {
        ProducerRecord<String, String> dead = new ProducerRecord<>(settings.deadLetterTopic(), record.key(), record.value());
        record.headers().forEach(h -> dead.headers().add(h));
        addHeader(dead, "dlt.error", error.getClass().getName() + ": " + error.getMessage());
        addHeader(dead, "dlt.original.topic", record.topic());
        addHeader(dead, "dlt.original.partition", String.valueOf(record.partition()));
        addHeader(dead, "dlt.original.offset", String.valueOf(record.offset()));
        addHeader(dead, "dlt.consumer.group", settings.groupId());
        try {
            deadLetters.send(dead).get(10, TimeUnit.SECONDS);
            clearRetry(partition);
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Could not dead-letter {}-{}@{}, will retry: {}", record.topic(), record.partition(),
                    record.offset(), e.toString());
            recordFailure(partition, record);
            pauseUntilRetry(partition, record);
            return false;
        }
    }

    private static void addHeader(ProducerRecord<String, String> record, String name, String value) {
        record.headers().add(name, String.valueOf(value).getBytes(StandardCharsets.UTF_8));
    }

    static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
