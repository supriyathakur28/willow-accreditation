package com.supriya.accreditation.command;

import com.supriya.accreditation.command.AccreditationCommandService.ExpiryOutcome;
import com.supriya.accreditation.support.IntegrationTest;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@MicronautTest(transactional = false)
@Property(name = "expiry.enabled", value = "true")
@Property(name = "expiry.initial-delay", value = "1h") // the test runs the job itself
class ExpiryJobIntegrationTest extends IntegrationTest {

    @Inject ExpiryJob job;
    @Inject AccreditationCommandService commands;

    @BeforeEach
    void clean() {
        resetDatabases();
    }

    @Test
    void expiresOnlyConfirmedAccreditationsUntouchedForThirtyDays() {
        final UUID stale = insert("CONFIRMED", daysAgo(31));
        final UUID recent = insert("CONFIRMED", daysAgo(29));
        final UUID oldPending = insert("PENDING", daysAgo(40));
        final UUID oldFailed = insert("FAILED", daysAgo(40));

        final ExpiryJob.Run run = job.expireStaleConfirmations();

        assertThat(run).isEqualTo(new ExpiryJob.Run(1, 0, 0));
        assertThat(statusOf(stale)).containsEntry("status", "EXPIRED").containsEntry("version", 3L)
                .containsEntry("updated_by", "system:expiry-job");
        assertThat(statusOf(recent)).containsEntry("status", "CONFIRMED");
        assertThat(statusOf(oldPending)).containsEntry("status", "PENDING");
        assertThat(statusOf(oldFailed)).containsEntry("status", "FAILED");

        final Map<String, Object> event = rows("accreditation_write", "SELECT event_type, payload FROM outbox_events").getFirst();
        assertThat(event.get("event_type")).isEqualTo("AccreditationExpired");
        assertThat((String) event.get("payload")).contains("\"performed_by\":\"system:expiry-job\"")
                .contains("\"previous_status\":\"CONFIRMED\"");
    }

    @Test
    @Timeout(30) // a cursor that restarted from the beginning would retry the locked row forever
    void aFailedRowIsNotRetriedWithinTheRunAndLaterPagesAreStillReached() throws Exception {
        // Oldest, so first on page 1; the PAGE_SIZE rows after it run over onto page 2
        final UUID locked = insert("CONFIRMED", daysAgo(40));
        insertStaleConfirmed(ExpiryJob.PAGE_SIZE);
        final String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/accreditation_write";
        try (Connection admin = DriverManager.getConnection(url, "root", MYSQL.getPassword())) {
            // An admin's transaction holds the row past the job's 3 s lock wait
            admin.setAutoCommit(false);
            admin.createStatement().executeQuery("SELECT * FROM accreditations WHERE id = '" + locked + "' FOR UPDATE");

            final ExpiryJob.Run run = job.expireStaleConfirmations();

            assertThat(run).isEqualTo(new ExpiryJob.Run(ExpiryJob.PAGE_SIZE, 0, 1));
            admin.rollback();
        }
        assertThat(statusOf(locked)).containsEntry("status", "CONFIRMED"); // left for the next run
        assertThat(rows("accreditation_write", "SELECT * FROM accreditations WHERE status = 'CONFIRMED'")).hasSize(1);
        assertThat(rows("accreditation_write", "SELECT * FROM outbox_events")).hasSize(ExpiryJob.PAGE_SIZE);
    }

    @Test
    void reChecksEligibilityOnTheLockedRow() {
        // The scan saw these as eligible, but an admin changed them before the job got the lock
        final UUID revoked = insert("FAILED", daysAgo(40));
        final UUID reconfirmed = insert("CONFIRMED", Instant.now());
        final Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);

        assertThat(commands.expireIfStale(revoked, cutoff)).isEqualTo(ExpiryOutcome.SKIPPED);
        assertThat(commands.expireIfStale(reconfirmed, cutoff)).isEqualTo(ExpiryOutcome.SKIPPED);
        assertThat(commands.expireIfStale(UUID.randomUUID(), cutoff)).isEqualTo(ExpiryOutcome.SKIPPED);
        assertThat(rows("accreditation_write", "SELECT * FROM outbox_events")).isEmpty();
    }

    @Test
    void skipsTheRunWhileAnotherInstanceHoldsTheJobLock() throws Exception {
        final UUID stale = insert("CONFIRMED", daysAgo(31));
        final String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/accreditation_write";
        try (Connection otherInstance = DriverManager.getConnection(url, "root", MYSQL.getPassword())) {
            otherInstance.createStatement().execute("SELECT GET_LOCK('job:ExpiryJob.scheduledRun', 0)");

            job.scheduledRun();
            assertThat(statusOf(stale)).containsEntry("status", "CONFIRMED");
        } // closing the connection releases the lock, as a crashed pod would

        // MySQL releases it once it has processed the disconnect, which can lag the client's close() slightly
        await().atMost(Duration.ofSeconds(5)).until(() -> Long.valueOf(1).equals(rows("accreditation_write",
                "SELECT IS_FREE_LOCK('job:ExpiryJob.scheduledRun') AS free").getFirst().get("free")));
        job.scheduledRun();
        assertThat(statusOf(stale)).containsEntry("status", "EXPIRED");
    }

    private static Instant daysAgo(final int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    private static UUID insert(
            final String status,
            final Instant updatedAt) {
        final UUID id = UUID.randomUUID();
        final String at = timestamp(updatedAt);
        sql("accreditation_write", """
                INSERT INTO accreditations (id, user_id, accreditation_type, status, version, created_by,
                    created_at, updated_at, updated_by)
                VALUES ('%s', '%s', 'BY_INCOME', '%s', 2, 'admin', '%s', '%s', 'reviewer')"""
                .formatted(id, "u" + id.toString().substring(0, 8), status, at, at));
        return id;
    }

    /** {@code count} stale CONFIRMED rows in one statement; shared timestamps exercise the id tie-break. */
    private static void insertStaleConfirmed(final int count) {
        final String values = IntStream.range(0, count)
                .mapToObj(i -> "('%s', 'u%d', 'BY_INCOME', 'CONFIRMED', 2, 'admin', '%s', '%3$s', 'reviewer')"
                        .formatted(UUID.randomUUID(), i, timestamp(daysAgo(31).minusSeconds(i % 7))))
                .collect(Collectors.joining(", "));
        sql("accreditation_write", """
                INSERT INTO accreditations (id, user_id, accreditation_type, status, version, created_by,
                    created_at, updated_at, updated_by)
                VALUES """ + values);
    }

    private static String timestamp(final Instant instant) {
        return instant.toString().replace("T", " ").replace("Z", "");
    }

    private static Map<String, Object> statusOf(final UUID id) {
        return rows("accreditation_write", "SELECT status, version, updated_by FROM accreditations WHERE id = '"
                + id + "'").getFirst();
    }
}
