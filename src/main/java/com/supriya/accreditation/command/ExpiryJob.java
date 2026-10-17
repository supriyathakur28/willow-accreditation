package com.supriya.accreditation.command;

import com.supriya.accreditation.command.AccreditationCommandService.ExpiryOutcome;
import com.supriya.accreditation.command.persistence.AccreditationRepository;
import com.supriya.accreditation.command.persistence.AccreditationRow;
import com.supriya.accreditation.platform.jobs.ScheduledJobLock;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Expires CONFIRMED accreditations whose last update is older than 30 days.
 * The scan is a plain keyset walk that only collects IDs; each ID is then expired in its own short transaction
 * through the same lock-and-decide path as an admin PUT. Expiries run on virtual threads, at most half the write
 * pool at once, so the job never starves admin writes of connections.
 */
@Singleton
@Requires(property = "expiry.enabled", notEquals = "false")
public class ExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(ExpiryJob.class);
    static final int PAGE_SIZE = 500;

    public record Run(
            int expired,
            int skipped,
            int failed) {
    }

    private final AccreditationRepository accreditations;
    private final AccreditationCommandService commands;
    private final TransactionOperations<Connection> transactions;
    private final Clock clock;
    private final Duration maxAge;
    private final int parallelism;

    ExpiryJob(
            final AccreditationRepository accreditations,
            final AccreditationCommandService commands,
            @Named("write") final TransactionOperations<Connection> transactions,
            final Clock clock,
            @Value("${expiry.max-age:30d}") final Duration maxAge,
            @Value("${datasources.write.maximum-pool-size:10}") final int writePoolSize) {
        this.accreditations = accreditations;
        this.commands = commands;
        this.transactions = transactions;
        this.clock = clock;
        this.maxAge = maxAge;
        this.parallelism = Math.max(1, writePoolSize / 2);
    }

    @Scheduled(fixedDelay = "${expiry.interval:1h}", initialDelay = "${expiry.initial-delay:1m}")
    @ScheduledJobLock
    public void scheduledRun() {
        final Run run = expireStaleConfirmations();
        if (run.expired() + run.failed() > 0) {
            log.info("Expiry run: {} expired, {} skipped, {} failed", run.expired(), run.skipped(), run.failed());
        }
    }

    /** One full pass. The cutoff is fixed once per run, from the injected clock. */
    public Run expireStaleConfirmations() {
        final Instant cutoff = clock.instant().minus(maxAge);
        int expired = 0;
        int skipped = 0;
        int failed = 0;
        Instant afterUpdatedAt = Instant.EPOCH;
        UUID afterId = new UUID(0, 0);
        final Semaphore permits = new Semaphore(parallelism);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            while (true) {
                final Instant pageAfterUpdatedAt = afterUpdatedAt;
                final UUID pageAfterId = afterId;
                final List<AccreditationRow> page = transactions.executeRead(status ->
                        accreditations.findExpiryCandidates(cutoff, pageAfterUpdatedAt, pageAfterId, PAGE_SIZE));
                if (page.isEmpty()) {
                    break;
                }
                final List<Future<ExpiryOutcome>> results = new ArrayList<>(page.size());
                for (AccreditationRow candidate : page) {
                    results.add(executor.submit(() -> {
                        permits.acquire();
                        try {
                            return commands.expireIfStale(candidate.id(), cutoff);
                        } finally {
                            permits.release();
                        }
                    }));
                }
                for (int i = 0; i < results.size(); i++) {
                    try {
                        if (results.get(i).get() == ExpiryOutcome.EXPIRED) {
                            expired++;
                        } else {
                            skipped++;
                        }
                    } catch (Exception e) {
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                            return new Run(expired, skipped, failed);
                        }
                        // One failure never stops the run; the row is picked up again next time
                        failed++;
                        log.warn("Could not expire {}: {}", page.get(i).id(), e.toString());
                    }
                }
                final AccreditationRow last = page.getLast();
                afterUpdatedAt = last.updatedAt();
                afterId = last.id();
            }
        }
        return new Run(expired, skipped, failed);
    }
}
