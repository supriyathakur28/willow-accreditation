package com.supriya.accreditation.concurrency;

import com.supriya.accreditation.command.ExpiryJob;
import com.supriya.accreditation.domain.AccreditationStatus;
import com.supriya.accreditation.support.ApiIntegrationTest;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 9b under real contention. Each test releases its requests together and then checks the invariant
 * on what was committed (the event chain in the outbox), not only on the responses.
 */
@MicronautTest(transactional = false)
@Property(name = "expiry.enabled", value = "true")
@Property(name = "expiry.initial-delay", value = "1h")
class ConcurrencyIntegrationTest extends ApiIntegrationTest {

    private static final List<String> OUTCOMES = List.of("CONFIRMED", "FAILED", "EXPIRED");

    @Inject ExpiryJob expiryJob;

    @BeforeEach
    void clean() throws Exception {
        resetDatabases();
        REDIS.execInContainer("redis-cli", "FLUSHALL");
        registerUser(USER);
    }

    @RepeatedTest(3)
    void parallelDecisionsOnOneAccreditationFormALegalChain() {
        String id = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST)));

        List<Reply> replies = concurrently(20, i -> send(put(id, OUTCOMES.get(i % OUTCOMES.size()))));

        assertThat(replies).extracting(Reply::status).allMatch(s -> s == 200 || s == 409);
        List<Map<String, Object>> chain = events(id);
        // Every accepted change starts from the state committed just before it
        AccreditationStatus current = AccreditationStatus.PENDING;
        long version = 1;
        for (Map<String, Object> event : chain.subList(1, chain.size())) {
            AccreditationStatus from = AccreditationStatus.valueOf((String) event.get("previous_status"));
            AccreditationStatus to = AccreditationStatus.valueOf((String) event.get("status"));
            assertThat(from).isEqualTo(current);
            assertThat(current.canMoveTo(to)).as("%s -> %s", from, to).isTrue();
            assertThat(((Number) event.get("version")).longValue()).isEqualTo(++version);
            current = to;
        }
        assertThat(current.isTerminal()).isTrue(); // 20 mixed requests always end in FAILED or EXPIRED
        assertThat(rows("accreditation_write", "SELECT status, version FROM accreditations").getFirst())
                .containsEntry("status", current.name()).containsEntry("version", version);
        // Every request for a different status after the terminal one was refused
        long refused = replies.stream().filter(r -> r.status() == 409).count();
        assertThat(refused).isPositive();
        assertThat(replies.stream().filter(r -> r.status() == 409))
                .allSatisfy(r -> assertThat(r.body()).containsEntry("error_code", "TRANSITION_NOT_ALLOWED"));
    }

    @RepeatedTest(3)
    void parallelSubmissionsForOneUserCreateExactlyOnePending() {
        List<Reply> replies = concurrently(20, i -> send(HttpRequest.POST("/user/accreditation", BRIEF_POST)));

        assertThat(replies).extracting(Reply::status).containsOnly(201, 409);
        assertThat(replies.stream().filter(r -> r.status() == 201)).hasSize(1);
        assertThat(rows("accreditation_write", "SELECT * FROM accreditations")).hasSize(1);
        assertThat(rows("accreditation_write", "SELECT * FROM outbox_events")).hasSize(1);
    }

    @Test
    void parallelSubmissionsForDifferentUsersDoNotContend() {
        List<String> users = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            users.add("user-" + i);
            registerUser("user-" + i);
        }

        List<Reply> replies = concurrently(20, i ->
                send(HttpRequest.POST("/user/accreditation", BRIEF_POST.replace(USER, users.get(i)))));

        assertThat(replies).extracting(Reply::status).containsOnly(201);
    }

    @RepeatedTest(3)
    void concurrentRetriesWithOneIdempotencyKeyCreateOneAccreditation() {
        List<Reply> replies = concurrently(10, i ->
                send(HttpRequest.POST("/user/accreditation", BRIEF_POST).header("Idempotency-Key", "same-key")));

        assertThat(replies).extracting(Reply::status).containsOnly(201);
        assertThat(replies.stream().map(r -> r.body().get("accreditation_id")).collect(Collectors.toSet())).hasSize(1);
        assertThat(rows("accreditation_write", "SELECT * FROM accreditations")).hasSize(1);
    }

    @RepeatedTest(3)
    void concurrentRequestsReusingAKeyWithDifferentBodiesCreateOneAccreditation() {
        List<Reply> replies = concurrently(10, i -> send(HttpRequest.POST("/user/accreditation",
                i % 2 == 0 ? BRIEF_POST : BRIEF_POST.replace("BY_INCOME", "BY_NET_WORTH"))
                .header("Idempotency-Key", "same-key")));

        // Whichever body claimed the key wins; the other body is refused, whether Redis or the database caught it
        assertThat(replies).extracting(Reply::status).containsOnly(201, 422);
        assertThat(rows("accreditation_write", "SELECT * FROM accreditations")).hasSize(1);
    }

    @RepeatedTest(3)
    void theExpiryJobRacingARevocationLeavesExactlyOneDecisionPerAccreditation() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            registerUser("racer-" + i);
            String id = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST.replace(USER, "racer-" + i))));
            send(put(id, "CONFIRMED"));
            ids.add(id);
        }
        sql("accreditation_write", "UPDATE accreditations SET updated_at = NOW(6) - INTERVAL 31 DAY");

        List<Object> results = concurrently(21, i -> i == 20
                ? expiryJob.expireStaleConfirmations()
                : send(put(ids.get(i), "FAILED")));

        for (String id : ids) {
            List<Map<String, Object>> chain = events(id);
            assertThat(chain).hasSize(3); // submitted, confirmed, then exactly one of expired or failed
            Map<String, Object> last = chain.getLast();
            assertThat(last.get("previous_status")).isEqualTo("CONFIRMED");
            assertThat(last.get("status")).isIn("EXPIRED", "FAILED");
        }
        ExpiryJob.Run run = (ExpiryJob.Run) results.getLast();
        long revoked = results.subList(0, 20).stream().filter(r -> ((Reply) r).status() == 200).count();
        assertThat(run.expired() + revoked).isEqualTo(20);
        assertThat(run.failed()).isZero();
    }

    @Test
    void twoJobInstancesExpireEachAccreditationOnce() {
        for (int i = 0; i < 50; i++) {
            registerUser("job-" + i);
            String id = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST.replace(USER, "job-" + i))));
            send(put(id, "CONFIRMED"));
        }
        sql("accreditation_write", "UPDATE accreditations SET updated_at = NOW(6) - INTERVAL 31 DAY");
        sql("accreditation_write", "DELETE FROM outbox_events");

        // Bypasses the job lock on purpose: even two overlapping runs cannot expire a row twice
        List<ExpiryJob.Run> runs = concurrently(2, i -> expiryJob.expireStaleConfirmations());

        assertThat(runs.get(0).expired() + runs.get(1).expired()).isEqualTo(50);
        assertThat(rows("accreditation_write", "SELECT * FROM outbox_events")).hasSize(50);
    }

    private List<Map<String, Object>> events(String accreditationId) {
        return rows("accreditation_write", "SELECT payload FROM outbox_events WHERE aggregate_id = UUID_TO_BIN('"
                + accreditationId + "') ORDER BY id").stream()
                .map(row -> parse((String) row.get("payload")))
                .toList();
    }

    /** Runs n tasks on virtual threads, released at the same instant. */
    private static <T> List<T> concurrently(int n, IntFunction<T> task) {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                Callable<T> callable = () -> {
                    start.await();
                    return task.apply(index);
                };
                futures.add(executor.submit(callable));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get());
            }
            return results;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
