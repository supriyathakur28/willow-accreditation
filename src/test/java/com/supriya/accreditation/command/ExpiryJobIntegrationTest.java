package com.supriya.accreditation.command;

import com.supriya.accreditation.command.AccreditationCommandService.ExpiryOutcome;
import com.supriya.accreditation.support.IntegrationTest;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

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
    void walksEveryPage() {
        for (int i = 0; i < 1_234; i++) { // more than two pages of 500
            insert("CONFIRMED", daysAgo(31).minusSeconds(i % 7)); // shared timestamps exercise the id tie-break
        }

        final ExpiryJob.Run run = job.expireStaleConfirmations();

        assertThat(run.expired()).isEqualTo(1_234);
        assertThat(rows("accreditation_write", "SELECT * FROM accreditations WHERE status = 'CONFIRMED'")).isEmpty();
        assertThat(rows("accreditation_write", "SELECT * FROM outbox_events")).hasSize(1_234);
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
        final String at = updatedAt.toString().replace("T", " ").replace("Z", "");
        sql("accreditation_write", """
                INSERT INTO accreditations (id, user_id, accreditation_type, status, version, created_by,
                    created_at, updated_at, updated_by)
                VALUES (UUID_TO_BIN('%s'), '%s', 'BY_INCOME', '%s', 2, 'admin', '%s', '%s', 'reviewer')"""
                .formatted(id, "u" + id.toString().substring(0, 8), status, at, at));
        return id;
    }

    private static Map<String, Object> statusOf(final UUID id) {
        return rows("accreditation_write", "SELECT status, version, updated_by FROM accreditations WHERE id = UUID_TO_BIN('"
                + id + "')").getFirst();
    }
}
