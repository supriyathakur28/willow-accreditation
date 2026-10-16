package com.supriya.accreditation.persistence;

import com.supriya.accreditation.command.persistence.AccreditationRepository;
import com.supriya.accreditation.command.persistence.AccreditationRow;
import com.supriya.accreditation.command.persistence.DocumentRepository;
import com.supriya.accreditation.command.persistence.DocumentRow;
import com.supriya.accreditation.command.persistence.OutboxEventRow;
import com.supriya.accreditation.command.persistence.OutboxRepository;
import com.supriya.accreditation.domain.AccreditationStatus;
import com.supriya.accreditation.platform.persistence.DuplicateKeys;
import com.supriya.accreditation.platform.UuidV7Generator;
import com.supriya.accreditation.query.persistence.AccreditationViewRepository;
import com.supriya.accreditation.query.persistence.AccreditationViewRow;
import com.supriya.accreditation.query.persistence.StatusHistoryRepository;
import com.supriya.accreditation.query.persistence.StatusHistoryRow;
import com.supriya.accreditation.support.IntegrationTest;
import com.supriya.accreditation.users.persistence.KnownUserRow;
import com.supriya.accreditation.users.persistence.KnownUserRepository;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static com.supriya.accreditation.domain.AccreditationStatus.CONFIRMED;
import static com.supriya.accreditation.domain.AccreditationStatus.EXPIRED;
import static com.supriya.accreditation.domain.AccreditationStatus.FAILED;
import static com.supriya.accreditation.domain.AccreditationStatus.PENDING;
import static com.supriya.accreditation.domain.AccreditationType.BY_INCOME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The schema and the repositories against a real MySQL 8.4: the rules the database enforces are tested here. */
@MicronautTest(startApplication = false, transactional = false)
class PersistenceIntegrationTest extends IntegrationTest {

    private static final String USER = "g8NlYJnk7zK9BlB1J2Ebjs0AkhCTpE1V";
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00.123456Z");

    @Inject @Named("write") TransactionOperations<Connection> writeTx;
    @Inject @Named("read") TransactionOperations<Connection> readTx;
    @Inject AccreditationRepository accreditations;
    @Inject DocumentRepository documents;
    @Inject OutboxRepository outbox;
    @Inject AccreditationViewRepository views;
    @Inject StatusHistoryRepository history;
    @Inject KnownUserRepository knownUsers;
    @Inject UuidV7Generator ids;

    @BeforeEach
    void emptyTables() {
        writeTx.executeWrite(s -> execute(s.getConnection(),
                "DELETE FROM accreditation_documents", "DELETE FROM accreditations",
                "DELETE FROM outbox_events", "DELETE FROM known_users"));
        readTx.executeWrite(s -> execute(s.getConnection(),
                "DELETE FROM accreditation_views", "DELETE FROM accreditation_status_history"));
    }

    @Nested
    class WriteModel {

        @Test
        void savesAndLocksAnAccreditation() {
            AccreditationRow saved = inWriteDb(() -> accreditations.save(pending(USER, "key-1")));

            AccreditationRow locked = inWriteDb(() -> accreditations.findByIdForUpdate(saved.id()).orElseThrow());

            assertThat(locked).isEqualTo(saved);
            assertThat(locked.toDomain().status()).isEqualTo(PENDING);
            assertThat(locked.createdAt()).isEqualTo(T0); // microseconds survive the round trip
        }

        @Test
        void storesIdsAsSixteenBigEndianBytes() {
            AccreditationRow saved = inWriteDb(() -> accreditations.save(pending(USER, null)));

            String hex = writeTx.executeRead(s -> queryString(s.getConnection(),
                    "SELECT HEX(id) FROM accreditations"));

            assertThat(hex).isEqualTo(saved.id().toString().replace("-", "").toUpperCase());
        }

        @Test
        void updatesStateAndKeepsCreationColumns() {
            AccreditationRow saved = inWriteDb(() -> accreditations.save(pending(USER, "key-1")));
            Instant later = T0.plusSeconds(60);

            inWriteDb(() -> accreditations.update(new AccreditationRow(saved.id(), USER, BY_INCOME, CONFIRMED,
                    2, "admin", "key-1", saved.requestSha256(), T0, later, "reviewer")));

            AccreditationRow reloaded = inWriteDb(() -> accreditations.findById(saved.id()).orElseThrow());
            assertThat(reloaded.status()).isEqualTo(CONFIRMED);
            assertThat(reloaded.version()).isEqualTo(2);
            assertThat(reloaded.updatedAt()).isEqualTo(later);
            assertThat(reloaded.updatedBy()).isEqualTo("reviewer");
            assertThat(reloaded.createdBy()).isEqualTo("admin");
        }

        @Test
        void rejectsASecondPendingForTheSameUser() {
            inWriteDb(() -> accreditations.save(pending(USER, null)));

            assertThatThrownBy(() -> inWriteDb(() -> accreditations.save(pending(USER, null))))
                    .satisfies(e -> assertThat(DuplicateKeys.violatedKey(e))
                            .contains("uq_accreditations_one_pending_per_user"));
        }

        @Test
        void allowsANewPendingOnceThePreviousOneIsDecided() {
            AccreditationRow first = inWriteDb(() -> accreditations.save(pending(USER, null)));
            inWriteDb(() -> accreditations.update(withStatus(first, FAILED)));

            inWriteDb(() -> accreditations.save(pending(USER, null)));

            assertThat(countAccreditations()).isEqualTo(2);
        }

        @Test
        void treatsUserIdsAsCaseSensitive() {
            inWriteDb(() -> accreditations.save(pending(USER, null)));

            inWriteDb(() -> accreditations.save(pending(USER.toUpperCase(), null)));

            assertThat(countAccreditations()).isEqualTo(2);
        }

        @Test
        void scopesIdempotencyKeysPerCaller() {
            inWriteDb(() -> accreditations.save(pending(USER, "key-1")));

            assertThatThrownBy(() -> inWriteDb(() -> accreditations.save(pending("otherUser", "key-1"))))
                    .satisfies(e -> assertThat(DuplicateKeys.violatedKey(e)).contains("uq_accreditations_idempotency_key"));

            AccreditationRow otherCaller = new AccreditationRow(ids.next(), "otherUser", BY_INCOME, PENDING, 1,
                    "another-admin", "key-1", "f".repeat(64), T0, T0, "another-admin");
            inWriteDb(() -> accreditations.save(otherCaller));
            assertThat(inWriteDb(() -> accreditations.findByCreatedByAndIdempotencyKey("another-admin", "key-1")))
                    .map(AccreditationRow::id).contains(otherCaller.id());
        }

        @Test
        void storesTheDocumentApartFromTheStatus() {
            AccreditationRow saved = inWriteDb(() -> accreditations.save(pending(USER, null)));
            byte[] pdf = "%PDF-1.7 test".getBytes();

            inWriteDb(() -> documents.save(new DocumentRow(saved.id(), "income.pdf", "application/pdf",
                    pdf.length, "a".repeat(64), pdf, T0)));

            DocumentRow reloaded = inWriteDb(() -> documents.findById(saved.id()).orElseThrow());
            assertThat(reloaded.content()).isEqualTo(pdf);
            assertThat(reloaded.fileName()).isEqualTo("income.pdf");
        }

        @Test
        void rejectsAnUnknownStatusAtTheDatabase() {
            assertThatThrownBy(() -> writeTx.executeWrite(s -> execute(s.getConnection(), """
                    INSERT INTO accreditations (id, user_id, accreditation_type, status, version, created_by,
                        created_at, updated_at, updated_by)
                    VALUES (UNHEX(REPLACE(UUID(), '-', '')), 'u1', 'BY_INCOME', 'APPROVED', 1, 'a', NOW(6), NOW(6), 'a')""")))
                    .hasMessageContaining("chk_accreditations_status");
        }
    }

    @Nested
    class ExpiryScan {

        @Test
        void pagesThroughConfirmedRowsOlderThanTheCutoffWithoutRepeats() {
            Instant cutoff = T0.plus(30, ChronoUnit.DAYS);
            // Five eligible rows, two of them sharing an updated_at, to exercise the id tie-break
            List<AccreditationRow> eligible = List.of(
                    confirmedAt("u1", T0), confirmedAt("u2", T0.plusSeconds(1)), confirmedAt("u3", T0.plusSeconds(1)),
                    confirmedAt("u4", T0.plusSeconds(2)), confirmedAt("u5", T0.plusSeconds(3)));
            AccreditationRow tooRecent = confirmedAt("u6", cutoff);
            AccreditationRow stillPending = pending("u7", null);
            inWriteDb(() -> {
                eligible.forEach(accreditations::save);
                accreditations.save(tooRecent);
                return accreditations.save(stillPending);
            });

            List<UUID> seen = new ArrayList<>();
            Instant afterUpdatedAt = Instant.EPOCH;
            UUID afterId = new UUID(0, 0);
            while (true) {
                Instant a = afterUpdatedAt;
                UUID b = afterId;
                List<AccreditationRow> page = inWriteDb(() -> accreditations.findExpiryCandidates(cutoff, a, b, 2));
                if (page.isEmpty()) {
                    break;
                }
                page.forEach(r -> seen.add(r.id()));
                AccreditationRow last = page.getLast();
                afterUpdatedAt = last.updatedAt();
                afterId = last.id();
            }

            assertThat(seen).containsExactlyInAnyOrderElementsOf(eligible.stream().map(AccreditationRow::id).toList())
                    .doesNotHaveDuplicates();
        }
    }

    @Nested
    class Outbox {

        @Test
        void returnsUnpublishedEventsInInsertOrderAndMarksThemPublished() {
            List<OutboxEventRow> saved = inWriteDb(() -> List.of(
                    outbox.save(event()), outbox.save(event()), outbox.save(event())));

            List<OutboxEventRow> batch = inWriteDb(() -> outbox.findUnpublished(2));
            assertThat(batch).extracting(OutboxEventRow::id).containsExactly(saved.get(0).id(), saved.get(1).id());
            assertThat(batch.getFirst().eventId()).isEqualTo(saved.getFirst().eventId());

            int marked = inWriteDb(() -> outbox.markPublished(List.of(saved.get(0).id(), saved.get(1).id()),
                    T0.plusSeconds(1)));

            assertThat(marked).isEqualTo(2);
            assertThat(inWriteDb(() -> outbox.findUnpublished(10)))
                    .extracting(OutboxEventRow::id).containsExactly(saved.get(2).id());
            assertThat(inWriteDb(() -> outbox.findOldestUnpublishedCreatedAt())).contains(T0);
        }

        @Test
        void deletesOnlyEventsPublishedBeforeTheCutoff() {
            List<OutboxEventRow> saved = inWriteDb(() -> List.of(
                    outbox.save(event()), outbox.save(event()), outbox.save(event())));
            inWriteDb(() -> outbox.markPublished(List.of(saved.get(0).id()), T0));
            inWriteDb(() -> outbox.markPublished(List.of(saved.get(1).id()), T0.plus(10, ChronoUnit.DAYS)));
            Instant cutoff = T0.plus(7, ChronoUnit.DAYS);

            List<Long> old = inWriteDb(() -> outbox.findPublishedBefore(cutoff, 100));
            // The unpublished and the recent ids are passed too: the repeated predicate must protect them
            int deleted = inWriteDb(() -> outbox.deletePublishedBefore(
                    List.of(old.getFirst(), saved.get(1).id(), saved.get(2).id()), cutoff));

            assertThat(old).containsExactly(saved.get(0).id());
            assertThat(deleted).isEqualTo(1);
            assertThat(inWriteDb(() -> outbox.findUnpublished(10))).hasSize(1);
        }

        @Test
        void hasNoLagWhenEverythingIsPublished() {
            assertThat(inWriteDb(() -> outbox.findOldestUnpublishedCreatedAt())).isEmpty();
        }
    }

    @Nested
    class ReadModel {

        @Test
        void appliesOnlyNewerVersions() {
            UUID id = ids.next();
            inReadDb(() -> views.save(new AccreditationViewRow(id, USER, BY_INCOME, PENDING, 1, T0, T0)));

            int applied = inReadDb(() -> views.applyIfNewer(id, EXPIRED, 3, T0.plusSeconds(3)));
            int late = inReadDb(() -> views.applyIfNewer(id, CONFIRMED, 2, T0.plusSeconds(2)));
            int duplicate = inReadDb(() -> views.applyIfNewer(id, EXPIRED, 3, T0.plusSeconds(3)));

            assertThat(applied).isEqualTo(1);
            assertThat(late).isZero();
            assertThat(duplicate).isZero();
            AccreditationViewRow view = inReadDb(() -> views.findById(id).orElseThrow());
            assertThat(view.status()).isEqualTo(EXPIRED);
            assertThat(view.version()).isEqualTo(3);
            assertThat(view.submittedAt()).isEqualTo(T0);
        }

        @Test
        void reportsAMissingViewSoTheConsumerCanInsertIt() {
            assertThat(inReadDb(() -> views.applyIfNewer(ids.next(), CONFIRMED, 2, T0))).isZero();
        }

        @Test
        void listsOnlyTheRequestedUsersAccreditations() {
            UUID first = ids.next();
            UUID second = ids.next();
            inReadDb(() -> {
                views.save(new AccreditationViewRow(second, USER, BY_INCOME, PENDING, 1, T0, T0));
                views.save(new AccreditationViewRow(first, USER, BY_INCOME, FAILED, 2, T0, T0));
                return views.save(new AccreditationViewRow(ids.next(), "someoneElse", BY_INCOME, PENDING, 1, T0, T0));
            });

            assertThat(inReadDb(() -> views.findByUserIdOrderByAccreditationId(USER)))
                    .extracting(AccreditationViewRow::accreditationId).containsExactly(first, second);
            assertThat(inReadDb(() -> views.findByUserIdOrderByAccreditationId(USER.toLowerCase()))).isEmpty();
        }

        @Test
        void keepsOneHistoryEntryPerEvent() {
            UUID accreditationId = ids.next();
            StatusHistoryRow submitted = new StatusHistoryRow(ids.next(), accreditationId, 1, "AccreditationSubmitted",
                    null, PENDING, "admin", T0);
            StatusHistoryRow confirmed = new StatusHistoryRow(ids.next(), accreditationId, 2, "AccreditationConfirmed",
                    PENDING, CONFIRMED, "reviewer", T0.plusSeconds(1));
            inReadDb(() -> {
                history.save(confirmed);
                return history.save(submitted);
            });

            assertThatThrownBy(() -> inReadDb(() -> history.save(confirmed)))
                    .satisfies(e -> assertThat(DuplicateKeys.violatedKey(e)).contains("PRIMARY"));
            assertThat(inReadDb(() -> history.findByAccreditationIdOrderByVersion(accreditationId)))
                    .containsExactly(submitted, confirmed);
        }

        @Test
        void recognisesARedeliveredEventByItsId() {
            UUID accreditationId = ids.next();
            StatusHistoryRow entry = new StatusHistoryRow(ids.next(), accreditationId, 1, "AccreditationSubmitted",
                    null, PENDING, "admin", T0);
            inReadDb(() -> history.save(entry));

            StatusHistoryRow sameEventOtherVersion = new StatusHistoryRow(entry.eventId(), accreditationId, 7,
                    "AccreditationSubmitted", null, PENDING, "admin", T0);
            assertThatThrownBy(() -> inReadDb(() -> history.save(sameEventOtherVersion)))
                    .satisfies(e -> assertThat(DuplicateKeys.violatedKey(e))
                            .contains("uq_accreditation_status_history_event_id"));
        }
    }

    @Nested
    class KnownUsers {

        @Test
        void insertsEachUserOnceAndKeepsTheFirstRegistration() {
            writeTx.executeWrite(s -> { knownUsers.insertIfAbsent(USER, T0); return null; });
            writeTx.executeWrite(s -> { knownUsers.insertIfAbsent(USER, T0.plusSeconds(5)); return null; });

            assertThat(inWriteDb(() -> knownUsers.findById(USER))).map(KnownUserRow::registeredAt).contains(T0);
            assertThat(inWriteDb(() -> knownUsers.existsById(USER))).isTrue();
        }

        @Test
        void checksExistenceUnderAShareLockAndCaseSensitively() {
            writeTx.executeWrite(s -> { knownUsers.insertIfAbsent(USER, T0); return null; });

            assertThat(inWriteDb(() -> knownUsers.existsForShare(USER))).isTrue();
            assertThat(inWriteDb(() -> knownUsers.existsForShare(USER.toLowerCase()))).isFalse();
        }
    }

    private <T> T inWriteDb(Supplier<T> work) {
        return writeTx.executeWrite(s -> work.get());
    }

    private <T> T inReadDb(Supplier<T> work) {
        return readTx.executeWrite(s -> work.get());
    }

    private AccreditationRow pending(String userId, String idempotencyKey) {
        return new AccreditationRow(ids.next(), userId, BY_INCOME, PENDING, 1, "admin", idempotencyKey,
                idempotencyKey == null ? null : "e".repeat(64), T0, T0, "admin");
    }

    private AccreditationRow confirmedAt(String userId, Instant updatedAt) {
        return new AccreditationRow(ids.next(), userId, BY_INCOME, CONFIRMED, 2, "admin", null, null,
                T0, updatedAt, "reviewer");
    }

    private static AccreditationRow withStatus(AccreditationRow row, AccreditationStatus status) {
        return new AccreditationRow(row.id(), row.userId(), row.accreditationType(), status, row.version() + 1,
                row.createdBy(), row.idempotencyKey(), row.requestSha256(), row.createdAt(), row.updatedAt(), "reviewer");
    }

    private OutboxEventRow event() {
        return OutboxEventRow.pending(ids.next(), ids.next(), "AccreditationSubmitted", "{}", "req-1", T0);
    }

    private long countAccreditations() {
        return Long.parseLong(writeTx.executeRead(s -> queryString(s.getConnection(), "SELECT COUNT(*) FROM accreditations")));
    }

    private static Void execute(Connection connection, String... sql) {
        try (Statement statement = connection.createStatement()) {
            for (String s : sql) {
                statement.execute(s);
            }
            return null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String queryString(Connection connection, String sql) {
        try (Statement statement = connection.createStatement(); var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
