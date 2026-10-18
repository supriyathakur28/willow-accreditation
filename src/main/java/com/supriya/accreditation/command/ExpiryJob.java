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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

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

    /** The totals of one run: each candidate contributes {@link #of(Outcome)}, and totals add up with {@link #plus}. */
    public record Run(
            int expired,
            int skipped,
            int failed) {

        static final Run NONE = new Run(0, 0, 0);

        static Run of(final Outcome outcome) {
            return switch (outcome) {
                case EXPIRED -> new Run(1, 0, 0);
                case SKIPPED -> new Run(0, 1, 0);
                case FAILED -> new Run(0, 0, 1);
            };
        }

        Run plus(final Run other) {
            return new Run(expired + other.expired, skipped + other.skipped, failed + other.failed);
        }
    }

    enum Outcome { EXPIRED, SKIPPED, FAILED }

    /** Keyset position: the next page starts after this (updated_at, id). */
    private record Cursor(
            Instant updatedAt,
            UUID id) {

        static final Cursor START = new Cursor(Instant.EPOCH, new UUID(0, 0));

        static Cursor after(final AccreditationRow row) {
            return new Cursor(row.updatedAt(), row.id());
        }
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
        final Semaphore permits = new Semaphore(parallelism);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            return pages(cutoff)
                    .flatMap(page -> expire(page, cutoff, executor, permits))
                    .map(Run::of)
                    .reduce(Run.NONE, Run::plus);
        }
    }

    /** Pages of candidates; each page is read only once the previous one has been fully processed. */
    private Stream<List<AccreditationRow>> pages(final Instant cutoff) {
        return Stream.iterate(
                page(cutoff, Cursor.START),
                page -> !page.isEmpty() && !Thread.currentThread().isInterrupted(),
                page -> page(cutoff, Cursor.after(page.getLast())));
    }

    private List<AccreditationRow> page(
            final Instant cutoff,
            final Cursor after) {
        return transactions.executeRead(status ->
                accreditations.findExpiryCandidates(cutoff, after.updatedAt(), after.id(), PAGE_SIZE));
    }

    /** Expires one page concurrently and yields the outcomes in page order. */
    private Stream<Outcome> expire(
            final List<AccreditationRow> page,
            final Instant cutoff,
            final ExecutorService executor,
            final Semaphore permits) {
        // toList() submits the whole page before the first join; a lazy stream would run the page one row at a time
        final List<Future<Outcome>> submitted = page.stream()
                .map(candidate -> executor.submit(() -> expireOne(candidate.id(), cutoff, permits)))
                .toList();
        return submitted.stream().map(ExpiryJob::join);
    }

    private Outcome expireOne(
            final UUID accreditationId,
            final Instant cutoff,
            final Semaphore permits) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Outcome.FAILED;
        }
        try {
            return commands.expireIfStale(accreditationId, cutoff) == ExpiryOutcome.EXPIRED
                    ? Outcome.EXPIRED
                    : Outcome.SKIPPED;
        } catch (RuntimeException e) {
            // One failure never stops the run; the row is picked up again next time
            log.warn("Could not expire {}: {}", accreditationId, e.toString());
            return Outcome.FAILED;
        } finally {
            permits.release();
        }
    }

    private static Outcome join(final Future<Outcome> outcome) {
        try {
            return outcome.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // also ends the paging
            return Outcome.FAILED;
        } catch (ExecutionException e) {
            log.warn("Expiry task failed: {}", e.getCause().toString());
            return Outcome.FAILED;
        }
    }
}
