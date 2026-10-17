package com.supriya.accreditation.command;

import com.supriya.accreditation.support.ApiIntegrationTest;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@MicronautTest(transactional = false)
@Property(name = "concurrency-limits.admin-writes", value = "1")
class ConcurrencyLimitIntegrationTest extends ApiIntegrationTest {

    @Test
    void shedsAdminWritesBeyondTheLimitAtOnce() throws Exception {
        resetDatabases();
        registerUser(USER);
        final String id = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST)));
        final String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/accreditation_write";
        try (Connection holder = DriverManager.getConnection(url, "root", MYSQL.getPassword())) {
            holder.setAutoCommit(false);
            holder.createStatement().execute("SELECT * FROM accreditations FOR UPDATE");
            // Takes the only permit and waits on the row lock
            final CompletableFuture<Reply> slow = CompletableFuture.supplyAsync(() -> send(put(id, "CONFIRMED")));
            await().pollDelay(Duration.ofMillis(500)).until(() -> true);

            final long start = System.nanoTime();
            final Reply shed = send(put(id, "FAILED"));
            final Duration waited = Duration.ofNanos(System.nanoTime() - start);

            assertThat(shed.status()).isEqualTo(503);
            assertThat(shed.header("Retry-After")).isEqualTo("1");
            assertThat(waited).isLessThan(Duration.ofSeconds(1));
            holder.rollback();
            assertThat(slow.get().status()).isIn(200, 503);
        }
        // The permit was released: the next write goes through
        assertThat(send(put(id, "CONFIRMED")).status()).isEqualTo(200);
    }
}
