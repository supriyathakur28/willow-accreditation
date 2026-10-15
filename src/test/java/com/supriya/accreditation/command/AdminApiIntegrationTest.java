package com.supriya.accreditation.command;

import com.supriya.accreditation.support.ApiIntegrationTest;
import io.micronaut.http.HttpRequest;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The admin API over HTTP against real MySQL and Redis: status codes, error envelope, side effects. */
@MicronautTest(transactional = false)
class AdminApiIntegrationTest extends ApiIntegrationTest {

    @BeforeEach
    void cleanState() throws Exception {
        resetDatabases();
        REDIS.execInContainer("redis-cli", "FLUSHALL");
        registerUser(USER);
    }

    @Nested
    class Submit {

        @Test
        void acceptsTheBriefsRequestAndRecordsStateDocumentAndEvent() {
            Reply reply = send(HttpRequest.POST("/user/accreditation", BRIEF_POST).header("X-Request-Id", "req-123"));

            assertThat(reply.status()).isEqualTo(201);
            assertThat(reply.body()).containsOnlyKeys("accreditation_id");
            assertThat(reply.header("X-Request-Id")).isEqualTo("req-123");
            String id = (String) reply.body().get("accreditation_id");
            assertThat(id.charAt(14)).isEqualTo('7'); // UUIDv7

            Map<String, Object> row = rows("accreditation_write", "SELECT user_id, accreditation_type, status, version, "
                    + "created_by, updated_by, created_at = updated_at AS same_time FROM accreditations "
                    + "WHERE id = UUID_TO_BIN('" + id + "')").getFirst();
            assertThat(row).containsEntry("user_id", USER).containsEntry("accreditation_type", "BY_INCOME")
                    .containsEntry("status", "PENDING").containsEntry("version", 1L)
                    .containsEntry("created_by", "anonymous").containsEntry("same_time", 1L);

            Map<String, Object> document = rows("accreditation_write", "SELECT file_name, mime_type, size_bytes, sha256 "
                    + "FROM accreditation_documents").getFirst();
            assertThat(document).containsEntry("file_name", "2018.pdf").containsEntry("mime_type", "application/pdf")
                    .containsEntry("size_bytes", 31);

            Map<String, Object> outbox = rows("accreditation_write", "SELECT event_type, request_id, payload, "
                    + "published_at FROM outbox_events").getFirst();
            assertThat(outbox).containsEntry("event_type", "AccreditationSubmitted").containsEntry("request_id", "req-123");
            assertThat(outbox.get("published_at")).isNull();
            Map<String, Object> event = parse((String) outbox.get("payload"));
            assertThat(event).containsEntry("event_type", "AccreditationSubmitted").containsEntry("schema_version", 1)
                    .containsEntry("accreditation_id", id).containsEntry("user_id", USER)
                    .containsEntry("status", "PENDING").containsEntry("version", 1)
                    .containsEntry("performed_by", "anonymous")
                    .containsEntry("document_sha256", document.get("sha256"))
                    .doesNotContainKey("previous_status");
            assertThat((String) event.get("occurred_at")).endsWith("Z");
        }

        @Test
        void rejectsASecondRequestWhileOneIsPending() {
            send(HttpRequest.POST("/user/accreditation", BRIEF_POST));

            Reply reply = send(HttpRequest.POST("/user/accreditation", BRIEF_POST));

            assertThat(reply.status()).isEqualTo(409);
            assertThat(reply.body()).containsEntry("error_code", "PENDING_ACCREDITATION_EXISTS")
                    .containsKey("request_id");
            assertThat(rows("accreditation_write", "SELECT * FROM outbox_events")).hasSize(1);
        }

        @Test
        void acceptsANewRequestOnceThePendingOneIsDecided() {
            String first = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST)));
            send(put(first, "FAILED"));

            assertThat(send(HttpRequest.POST("/user/accreditation", BRIEF_POST)).status()).isEqualTo(201);
        }

        @Test
        void rejectsAnUnknownUser() {
            Reply reply = send(HttpRequest.POST("/user/accreditation", BRIEF_POST.replace(USER, "nobody")));

            assertThat(reply.status()).isEqualTo(422);
            assertThat(reply.body()).containsEntry("error_code", "USER_NOT_FOUND");
            assertThat(rows("accreditation_write", "SELECT * FROM accreditations")).isEmpty();
        }

        @Test
        void listsEveryInvalidField() {
            Reply reply = send(HttpRequest.POST("/user/accreditation", """
                    {"user_id": "has spaces", "accreditation_type": "BY_LUCK",
                     "document": {"name": "", "mime_type": "text/html", "content": "not base64!"}}"""));

            assertThat(reply.status()).isEqualTo(400);
            assertThat(reply.body()).containsEntry("error_code", "VALIDATION_FAILED");
            assertThat(invalidFields(reply)).containsExactly(
                    "accreditation_type:INVALID_VALUE", "document.content:INVALID_BASE64",
                    "document.mime_type:INVALID_VALUE", "document.name:REQUIRED", "user_id:INVALID_VALUE");
        }

        @Test
        void reportsMissingFields() {
            Reply reply = send(HttpRequest.POST("/user/accreditation", "{\"accreditation_type\": \"BY_INCOME\"}"));

            assertThat(reply.status()).isEqualTo(400);
            assertThat(invalidFields(reply)).containsExactly("document:REQUIRED", "user_id:REQUIRED");
        }

        @Test
        void rejectsUnknownFieldsAndMalformedJson() {
            Reply unknownField = send(HttpRequest.POST("/user/accreditation",
                    BRIEF_POST.replace("\"user_id\"", "\"admin\": true, \"user_id\"")));
            Reply malformed = send(HttpRequest.POST("/user/accreditation", "{\"user_id\": "));
            Reply empty = send(HttpRequest.POST("/user/accreditation", ""));

            assertThat(List.of(unknownField, malformed, empty)).allSatisfy(reply -> {
                assertThat(reply.status()).isEqualTo(400);
                assertThat(reply.body()).containsEntry("error_code", "VALIDATION_FAILED").containsKey("request_id");
            });
        }

        @Test
        void rejectsADocumentOverFiveMegabytesWithoutDecodingIt() {
            String content = "A".repeat(7_000_000); // valid base64, ~5.25 MB decoded, still under the 7 MB body cap
            Reply reply = send(HttpRequest.POST("/user/accreditation",
                    BRIEF_POST.replace("ICAiQC8qIjogWyJzcmMvKiJdCiAgICB9CiAgfQp9Cg==", content)));

            assertThat(reply.status()).isEqualTo(400);
            assertThat(invalidFields(reply)).containsExactly("document.content:INVALID_BASE64");
        }

        @Test
        void rejectsABodyOverTheServerLimit() {
            String content = "A".repeat(8_000_000);
            Reply reply = send(HttpRequest.POST("/user/accreditation",
                    BRIEF_POST.replace("ICAiQC8qIjogWyJzcmMvKiJdCiAgICB9CiAgfQp9Cg==", content)));

            assertThat(reply.status()).isEqualTo(413);
            assertThat(reply.body()).containsEntry("error_code", "PAYLOAD_TOO_LARGE");
        }

        @Test
        void keepsOnlyTheLastSegmentOfTheFileName() {
            send(HttpRequest.POST("/user/accreditation", BRIEF_POST.replace("2018.pdf", "../../etc/2018.pdf")));

            assertThat(rows("accreditation_write", "SELECT file_name FROM accreditation_documents").getFirst())
                    .containsEntry("file_name", "2018.pdf");
        }
    }

    @Nested
    class IdempotencyKey {

        @Test
        void aRetryReturnsTheOriginalAccreditation() {
            Reply first = send(HttpRequest.POST("/user/accreditation", BRIEF_POST).header("Idempotency-Key", "k-1"));
            Reply retry = send(HttpRequest.POST("/user/accreditation", BRIEF_POST).header("Idempotency-Key", "k-1"));

            assertThat(first.status()).isEqualTo(201);
            assertThat(retry.status()).isEqualTo(201);
            assertThat(idOf(retry)).isEqualTo(idOf(first));
            assertThat(rows("accreditation_write", "SELECT * FROM outbox_events")).hasSize(1);
        }

        @Test
        void aRetryAfterTheAccreditationWasDecidedStillReturnsIt() {
            String id = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST).header("Idempotency-Key", "k-1")));
            send(put(id, "CONFIRMED"));

            Reply retry = send(HttpRequest.POST("/user/accreditation", BRIEF_POST).header("Idempotency-Key", "k-1"));

            assertThat(retry.status()).isEqualTo(201);
            assertThat(idOf(retry)).isEqualTo(id);
        }

        @Test
        void aReusedKeyWithADifferentBodyIsRejectedByRedis() {
            send(HttpRequest.POST("/user/accreditation", BRIEF_POST).header("Idempotency-Key", "k-1"));

            Reply reply = send(HttpRequest.POST("/user/accreditation", BRIEF_POST.replace("BY_INCOME", "BY_NET_WORTH"))
                    .header("Idempotency-Key", "k-1"));

            assertThat(reply.status()).isEqualTo(422);
            assertThat(reply.body()).containsEntry("error_code", "IDEMPOTENCY_KEY_REUSED");
        }

        @Test
        void theDatabaseCatchesAReusedKeyWhenRedisHasForgottenIt() throws Exception {
            send(HttpRequest.POST("/user/accreditation", BRIEF_POST).header("Idempotency-Key", "k-1"));
            REDIS.execInContainer("redis-cli", "FLUSHALL");

            Reply reply = send(HttpRequest.POST("/user/accreditation", BRIEF_POST.replace("BY_INCOME", "BY_NET_WORTH"))
                    .header("Idempotency-Key", "k-1"));

            assertThat(reply.status()).isEqualTo(422);
            assertThat(reply.body()).containsEntry("error_code", "IDEMPOTENCY_KEY_REUSED");
        }

        @Test
        void equivalentJsonHashesTheSame() {
            send(HttpRequest.POST("/user/accreditation", BRIEF_POST).header("Idempotency-Key", "k-1"));

            Reply reformatted = send(HttpRequest.POST("/user/accreditation", BRIEF_POST.replaceAll("\\s+", ""))
                    .header("Idempotency-Key", "k-1"));

            assertThat(reformatted.status()).isEqualTo(201);
        }

        @Test
        void rejectsAMalformedKey() {
            Reply reply = send(HttpRequest.POST("/user/accreditation", BRIEF_POST).header("Idempotency-Key", "a b"));

            assertThat(reply.status()).isEqualTo(400);
            assertThat(invalidFields(reply)).singleElement().asString().endsWith(":INVALID_VALUE");
        }
    }

    @Nested
    class Finalise {

        @Test
        void confirmsThenRevokesAndRefusesToChangeAFailedAccreditation() {
            String id = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST)));

            Reply confirmed = send(put(id, "CONFIRMED"));
            assertThat(confirmed.status()).isEqualTo(200);
            assertThat(confirmed.body()).containsExactlyEntriesOf(Map.of("accreditation_id", id));
            assertThat(confirmed.header("ETag")).isEqualTo("\"2\"");

            Reply revoked = send(put(id, "FAILED"));
            assertThat(revoked.status()).isEqualTo(200);
            assertThat(revoked.header("ETag")).isEqualTo("\"3\"");

            Reply refused = send(put(id, "CONFIRMED"));
            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.body()).containsEntry("error_code", "TRANSITION_NOT_ALLOWED")
                    .containsEntry("current_status", "FAILED")
                    .containsEntry("message", "Cannot change status from FAILED to CONFIRMED");

            assertThat(rows("accreditation_write", "SELECT event_type FROM outbox_events ORDER BY id"))
                    .extracting(r -> r.get("event_type"))
                    .containsExactly("AccreditationSubmitted", "AccreditationConfirmed", "AccreditationFailed");
            Map<String, Object> failed = parse((String) rows("accreditation_write",
                    "SELECT payload FROM outbox_events ORDER BY id DESC LIMIT 1").getFirst().get("payload"));
            assertThat(failed).containsEntry("previous_status", "CONFIRMED").containsEntry("status", "FAILED")
                    .containsEntry("version", 3).doesNotContainKey("document_sha256");
        }

        @Test
        void repeatingTheCurrentStatusIsANoOpEvenWhenTerminal() {
            String id = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST)));
            send(put(id, "EXPIRED"));
            Object updatedAt = rows("accreditation_write", "SELECT updated_at FROM accreditations").getFirst().get("updated_at");

            Reply repeat = send(put(id, "EXPIRED"));

            assertThat(repeat.status()).isEqualTo(200);
            assertThat(repeat.header("ETag")).isEqualTo("\"2\"");
            assertThat(rows("accreditation_write", "SELECT * FROM outbox_events")).hasSize(2);
            assertThat(rows("accreditation_write", "SELECT updated_at FROM accreditations").getFirst())
                    .containsEntry("updated_at", updatedAt);
        }

        @Test
        void acceptsTheIdInUpperCase() {
            String id = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST)));

            assertThat(send(put(id.toUpperCase(), "CONFIRMED")).status()).isEqualTo(200);
        }

        @Test
        void answersNotFoundForUnknownAndMalformedIds() {
            Reply unknown = send(put("0192a1c9-5a20-7b11-8f0e-6a2d9c4b7f31", "CONFIRMED"));
            Reply malformed = send(put("not-a-uuid", "CONFIRMED"));
            Reply lenientJavaFormat = send(put("1-2-3-4-5", "CONFIRMED"));

            assertThat(List.of(unknown, malformed, lenientJavaFormat)).allSatisfy(reply -> {
                assertThat(reply.status()).isEqualTo(404);
                assertThat(reply.body()).containsEntry("error_code", "ACCREDITATION_NOT_FOUND");
            });
        }

        @Test
        void rejectsPendingAsAnOutcome() {
            String id = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST)));

            Reply reply = send(put(id, "PENDING"));

            assertThat(reply.status()).isEqualTo(400);
            assertThat(invalidFields(reply)).containsExactly("outcome:INVALID_VALUE");
        }

        @Test
        void rejectsAStaleDecisionWithIfMatch() {
            String id = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST)));
            send(put(id, "CONFIRMED")); // another admin got there first; now at version 2

            Reply stale = send(put(id, "FAILED").header("If-Match", "\"1\""));
            Reply current = send(put(id, "FAILED").header("If-Match", "\"2\""));

            assertThat(stale.status()).isEqualTo(412);
            assertThat(stale.body()).containsEntry("error_code", "VERSION_MISMATCH").containsEntry("current_status", "CONFIRMED");
            assertThat(current.status()).isEqualTo(200);
            assertThat(current.header("ETag")).isEqualTo("\"3\"");
        }

        @Test
        void rejectsAMalformedIfMatch() {
            String id = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST)));

            Reply reply = send(put(id, "CONFIRMED").header("If-Match", "\"v2\""));

            assertThat(reply.status()).isEqualTo(400);
            assertThat(invalidFields(reply)).containsExactly("If-Match:INVALID_VALUE");
        }

        @Test
        void rejectsAKeyReusedOnADifferentAccreditation() {
            String first = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST)));
            send(put(first, "FAILED").header("Idempotency-Key", "put-1"));
            String second = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST)));

            Reply sameKeyOtherTarget = send(put(second, "FAILED").header("Idempotency-Key", "put-1"));
            Reply sameKeySameTargetOtherBody = send(put(first, "CONFIRMED").header("Idempotency-Key", "put-1"));

            // The route is part of the key, so a different accreditation is a fresh claim
            assertThat(sameKeyOtherTarget.status()).isEqualTo(200);
            assertThat(sameKeySameTargetOtherBody.status()).isEqualTo(422);
        }
    }

    @Nested
    class Resilience {

        @Test
        void answers503WhenTheRowStaysLockedPastTheLockWait() throws Exception {
            String id = idOf(send(HttpRequest.POST("/user/accreditation", BRIEF_POST)));
            String url = "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/accreditation_write";
            try (Connection holder = DriverManager.getConnection(url, "root", MYSQL.getPassword())) {
                holder.setAutoCommit(false);
                holder.createStatement().execute("SELECT * FROM accreditations WHERE id = UUID_TO_BIN('" + id + "') FOR UPDATE");

                long start = System.nanoTime();
                Reply reply = send(put(id, "CONFIRMED"));
                Duration waited = Duration.ofNanos(System.nanoTime() - start);

                assertThat(reply.status()).isEqualTo(503);
                assertThat(reply.header("Retry-After")).isEqualTo("1");
                assertThat(reply.body()).containsEntry("error_code", "SERVICE_UNAVAILABLE");
                assertThat(waited).isBetween(Duration.ofSeconds(3), Duration.ofSeconds(6));
                holder.rollback();
            }
            assertThat(send(put(id, "CONFIRMED")).status()).isEqualTo(200);
        }

        @Test
        void keepsAcceptingWritesWhileRedisIsDown() {
            var docker = REDIS.getDockerClient();
            docker.pauseContainerCmd(REDIS.getContainerId()).exec();
            try {
                Reply first = send(HttpRequest.POST("/user/accreditation", BRIEF_POST).header("Idempotency-Key", "k-1"));
                Reply retry = send(HttpRequest.POST("/user/accreditation", BRIEF_POST).header("Idempotency-Key", "k-1"));

                assertThat(first.status()).isEqualTo(201);
                assertThat(idOf(retry)).isEqualTo(idOf(first)); // the database unique key still deduplicates
            } finally {
                docker.unpauseContainerCmd(REDIS.getContainerId()).exec();
            }
        }
    }

    @Nested
    class Envelope {

        @Test
        void answersTheRequestIdOnSecurityAndRoutingErrorsToo() {
            Reply unknownRoute = send(HttpRequest.GET("/nowhere").header("X-Request-Id", "trace-1"));

            assertThat(unknownRoute.header("X-Request-Id")).isEqualTo("trace-1");
            assertThat(unknownRoute.body()).containsEntry("request_id", "trace-1");
        }


        @Test
        void replacesAnUnsafeRequestId() {
            Reply reply = send(HttpRequest.POST("/user/accreditation", BRIEF_POST).header("X-Request-Id", "bad id <script>"));

            assertThat(reply.header("X-Request-Id")).matches("[0-9a-f-]{36}");
        }

        @Test
        void wrapsFrameworkErrorsToo() {
            Reply unknownRoute = send(HttpRequest.GET("/nowhere"));
            Reply wrongMethod = send(HttpRequest.DELETE("/user/accreditation"));

            assertThat(unknownRoute.status()).isEqualTo(404);
            assertThat(unknownRoute.body()).containsEntry("error_code", "NOT_FOUND").containsKey("request_id");
            assertThat(wrongMethod.status()).isEqualTo(405);
            assertThat(wrongMethod.body()).containsEntry("error_code", "METHOD_NOT_ALLOWED");
        }
    }
}
