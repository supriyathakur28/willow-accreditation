package com.supriya.accreditation.command;

import com.supriya.accreditation.command.persistence.OutboxEventRow;
import com.supriya.accreditation.command.persistence.OutboxRepository;
import com.supriya.accreditation.platform.jobs.ScheduledJobLock;
import com.supriya.accreditation.platform.messaging.Topics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes outbox rows to Kafka in id order, at least once.
 * A batch is sent asynchronously, flushed, then marked published up to the first failed send, so a later version
 * of an accreditation is never published ahead of an earlier one. Duplicates (a crash between send and mark) are
 * absorbed by the consumers.
 */
@Singleton
@Requires(property = "outbox.relay.enabled", notEquals = "false")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    static final int BATCH_SIZE = 100;
    static final int CLEANUP_BATCH_SIZE = 500;
    static final Duration KEEP_PUBLISHED = Duration.ofDays(7);

    private final OutboxRepository outbox;
    private final TransactionOperations<Connection> transactions;
    private final Producer<String, String> producer;
    private final Clock clock;
    private final AtomicLong oldestUnpublishedAgeSeconds = new AtomicLong();

    OutboxRelay(
            final OutboxRepository outbox,
            @Named("write") final TransactionOperations<Connection> transactions,
            final Producer<String, String> producer,
            final Clock clock,
            final MeterRegistry meters) {
        this.outbox = outbox;
        this.transactions = transactions;
        this.producer = producer;
        this.clock = clock;
        // Alert when this exceeds 30 s: events are not reaching Kafka
        Gauge.builder("outbox.oldest.unpublished.age", oldestUnpublishedAgeSeconds, AtomicLong::get)
                .baseUnit("seconds").register(meters);
    }

    @Scheduled(fixedDelay = "${outbox.relay.interval:500ms}")
    @ScheduledJobLock // one relay at a time keeps the publish order
    public void publishPending() {
        try {
            int published;
            do {
                published = publishBatch();
            } while (published == BATCH_SIZE);
            updateLag();
        } catch (RuntimeException e) {
            log.warn("Outbox relay run failed, will retry: {}", e.toString());
        }
    }

    /** Returns how many rows were published; fewer than a full batch means stop for this run. */
    int publishBatch() {
        final List<OutboxEventRow> batch = transactions.executeRead(status -> outbox.findUnpublished(BATCH_SIZE));
        if (batch.isEmpty()) {
            return 0;
        }
        final List<Future<RecordMetadata>> sends = new ArrayList<>(batch.size());
        try {
            for (OutboxEventRow row : batch) {
                sends.add(producer.send(toRecord(row)));
            }
        } catch (RuntimeException e) {
            // Thrown synchronously, for example when the broker is unreachable (max.block.ms); keep the futures we have
            log.warn("Outbox send failed after {} of {} events: {}", sends.size(), batch.size(), e.toString());
        }
        producer.flush();

        final List<Long> acknowledged = new ArrayList<>();
        for (int i = 0; i < sends.size(); i++) {
            try {
                sends.get(i).get(30, TimeUnit.SECONDS);
                acknowledged.add(batch.get(i).id());
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("Outbox event {} not acknowledged; stopping the batch here: {}", batch.get(i).eventId(), e.toString());
                break;
            }
        }
        if (!acknowledged.isEmpty()) {
            final Instant now = clock.instant();
            transactions.executeWrite(status -> outbox.markPublished(acknowledged, now));
        }
        return acknowledged.size() == batch.size() ? batch.size() : 0;
    }

    @Scheduled(fixedDelay = "${outbox.cleanup.interval:1h}", initialDelay = "1m")
    @ScheduledJobLock
    public void deleteOldPublished() {
        final Instant cutoff = clock.instant().minus(KEEP_PUBLISHED);
        int deleted;
        do {
            final List<Long> ids = transactions.executeRead(status -> outbox.findPublishedBefore(cutoff, CLEANUP_BATCH_SIZE));
            deleted = ids.isEmpty() ? 0 : transactions.executeWrite(status -> outbox.deletePublishedBefore(ids, cutoff));
        } while (deleted == CLEANUP_BATCH_SIZE);
    }

    private void updateLag() {
        final Instant now = clock.instant();
        final long age = transactions.executeRead(status -> outbox.findOldestUnpublishedCreatedAt())
                .map(created -> Duration.between(created, now).toSeconds())
                .orElse(0L);
        oldestUnpublishedAgeSeconds.set(Math.max(age, 0));
    }

    private static ProducerRecord<String, String> toRecord(final OutboxEventRow row) {
        final ProducerRecord<String, String> record =
                new ProducerRecord<>(Topics.ACCREDITATION_EVENTS, row.aggregateId().toString(), row.payload());
        record.headers().add("event_type", row.eventType().getBytes(StandardCharsets.UTF_8));
        if (row.requestId() != null) {
            record.headers().add("X-Request-Id", row.requestId().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
