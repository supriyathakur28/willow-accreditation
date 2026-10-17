package com.supriya.accreditation.domain;

import com.supriya.accreditation.domain.AccreditationCommand.ExpireIfStale;
import com.supriya.accreditation.domain.AccreditationCommand.Finalise;
import com.supriya.accreditation.domain.AccreditationEvent.AccreditationConfirmed;
import com.supriya.accreditation.domain.AccreditationEvent.AccreditationExpired;
import com.supriya.accreditation.domain.AccreditationEvent.AccreditationFailed;
import com.supriya.accreditation.domain.AccreditationEvent.AccreditationSubmitted;
import com.supriya.accreditation.domain.Decision.Accepted;
import com.supriya.accreditation.domain.Decision.NoOp;
import com.supriya.accreditation.domain.Decision.Rejected;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;

import static com.supriya.accreditation.domain.AccreditationStatus.CONFIRMED;
import static com.supriya.accreditation.domain.AccreditationStatus.EXPIRED;
import static com.supriya.accreditation.domain.AccreditationStatus.FAILED;
import static com.supriya.accreditation.domain.AccreditationStatus.PENDING;
import static org.assertj.core.api.Assertions.assertThat;

class AccreditationDeciderTest {

    private static final Instant CREATED = Instant.parse("2026-01-01T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final UUID ID = UUID.fromString("0192a1c9-5a20-7b11-8f0e-6a2d9c4b7f31");
    private static final UUID EVENT_ID = UUID.fromString("0192a1d0-6f1d-7c3a-9a51-3c1f0b7d2e10");
    private static final String USER = "g8NlYJnk7zK9BlB1J2Ebjs0AkhCTpE1V";

    private static Accreditation state(AccreditationStatus status, long version, Instant updatedAt) {
        return new Accreditation(ID, USER, AccreditationType.BY_INCOME, status, version, CREATED, updatedAt, "admin-1");
    }

    private static Accreditation state(AccreditationStatus status) {
        return state(status, 2, CREATED);
    }

    private static Finalise finalise(Outcome outcome) {
        return new Finalise(outcome, "admin-2", OptionalLong.empty());
    }

    @Nested
    class Finalising {

        /** The full transition table, written out by hand rather than derived from the code under test. */
        @ParameterizedTest(name = "{0} + {1} -> {2}")
        @CsvSource({
                "PENDING,   CONFIRMED, ACCEPTED",
                "PENDING,   FAILED,    ACCEPTED",
                "PENDING,   EXPIRED,   ACCEPTED",
                "CONFIRMED, CONFIRMED, NO_OP",
                "CONFIRMED, FAILED,    ACCEPTED",
                "CONFIRMED, EXPIRED,   ACCEPTED",
                "FAILED,    FAILED,    NO_OP",
                "FAILED,    CONFIRMED, REJECTED",
                "FAILED,    EXPIRED,   REJECTED",
                "EXPIRED,   EXPIRED,   NO_OP",
                "EXPIRED,   CONFIRMED, REJECTED",
                "EXPIRED,   FAILED,    REJECTED",
        })
        void transitionTable(AccreditationStatus current, Outcome outcome, String expected) {
            Decision decision = AccreditationDecider.decide(state(current), finalise(outcome), NOW, EVENT_ID);

            switch (expected) {
                case "ACCEPTED" -> assertThat(decision).isInstanceOf(Accepted.class);
                case "NO_OP" -> assertThat(decision).isInstanceOf(NoOp.class);
                case "REJECTED" -> assertThat(decision)
                        .isEqualTo(new Rejected(Decision.Reason.TRANSITION_NOT_ALLOWED, state(current)));
                default -> throw new IllegalArgumentException(expected);
            }
        }

        @Test
        void acceptedEventCarriesTheFullSnapshot() {
            Decision decision = AccreditationDecider.decide(state(PENDING), finalise(Outcome.CONFIRMED), NOW, EVENT_ID);

            assertThat(decision).isEqualTo(new Accepted(new AccreditationConfirmed(
                    EVENT_ID, ID, USER, AccreditationType.BY_INCOME, PENDING, 3, CREATED, NOW, "admin-2")));
        }

        @Test
        void revocationOfAConfirmedAccreditationRecordsThePreviousStatus() {
            Decision decision = AccreditationDecider.decide(state(CONFIRMED), finalise(Outcome.FAILED), NOW, EVENT_ID);

            assertThat(decision).isInstanceOfSatisfying(Accepted.class, accepted -> {
                assertThat(accepted.event()).isInstanceOf(AccreditationFailed.class);
                assertThat(accepted.event().previousStatus()).isEqualTo(CONFIRMED);
            });
        }

        @Test
        void staleExpectedVersionIsRejected() {
            var command = new Finalise(Outcome.FAILED, "admin-2", OptionalLong.of(1));

            Decision decision = AccreditationDecider.decide(state(CONFIRMED, 2, CREATED), command, NOW, EVENT_ID);

            assertThat(decision).isEqualTo(new Rejected(Decision.Reason.VERSION_MISMATCH, state(CONFIRMED, 2, CREATED)));
        }

        @Test
        void matchingExpectedVersionIsAccepted() {
            var command = new Finalise(Outcome.FAILED, "admin-2", OptionalLong.of(2));

            assertThat(AccreditationDecider.decide(state(CONFIRMED, 2, CREATED), command, NOW, EVENT_ID))
                    .isInstanceOf(Accepted.class);
        }

        @Test
        void repeatingTheCurrentStatusIsANoOpEvenWithAStaleVersion() {
            // A retried PUT whose first attempt succeeded: the state already holds, so the retry gets the same 200.
            var command = new Finalise(Outcome.FAILED, "admin-2", OptionalLong.of(2));
            Accreditation failed = state(FAILED, 3, NOW);

            assertThat(AccreditationDecider.decide(failed, command, NOW, EVENT_ID)).isEqualTo(new NoOp(failed));
        }
    }

    @Nested
    class Expiring {

        private final Instant cutoff = NOW.minus(Duration.ofDays(30));
        private final ExpireIfStale expire = new ExpireIfStale(cutoff, AccreditationDecider.EXPIRY_JOB_ACTOR);

        @Test
        void confirmedAndOlderThanTheCutoffIsExpired() {
            Accreditation stale = state(CONFIRMED, 2, cutoff.minusSeconds(1));

            Decision decision = AccreditationDecider.decide(stale, expire, NOW, EVENT_ID);

            assertThat(decision).isEqualTo(new Accepted(new AccreditationExpired(
                    EVENT_ID, ID, USER, AccreditationType.BY_INCOME, CONFIRMED, 3, CREATED, NOW,
                    AccreditationDecider.EXPIRY_JOB_ACTOR)));
        }

        /**
         * The job expires only CONFIRMED accreditations last updated before the cutoff (requirement 5). Anything else is
         * left alone: a recent confirmation, and every other status, even an old one. Expiring a PENDING accreditation
         * is an admin decision, made through the API.
         */
        @ParameterizedTest(name = "{0}, updated {1} days before the cutoff")
        @CsvSource({
                "CONFIRMED, 0",
                "CONFIRMED, -1",
                "PENDING,   5",
                "FAILED,    5",
                "EXPIRED,   5",
        })
        void isNotEligibleForTheJob(AccreditationStatus status, int daysBeforeCutoff) {
            Accreditation accreditation = state(status, 2, cutoff.minus(Duration.ofDays(daysBeforeCutoff)));

            assertThat(AccreditationDecider.decide(accreditation, expire, NOW, EVENT_ID))
                    .isEqualTo(new Rejected(Decision.Reason.NOT_ELIGIBLE_FOR_EXPIRY, accreditation));
        }
    }

    @Nested
    class SubmittingAndEvolving {

        @Test
        void submissionCreatesAPendingAccreditationAtVersionOne() {
            AccreditationSubmitted submitted = AccreditationDecider.submit(
                    ID, USER, AccreditationType.BY_NET_WORTH, "abc123", "admin-1", NOW, EVENT_ID);

            assertThat(submitted.previousStatus()).isNull();
            assertThat(submitted.eventType()).isEqualTo("AccreditationSubmitted");
            assertThat(AccreditationDecider.evolve(null, submitted)).isEqualTo(new Accreditation(
                    ID, USER, AccreditationType.BY_NET_WORTH, PENDING, 1, NOW, NOW, "admin-1"));
        }

        @Test
        void eachAcceptedChangeIsAppliedInTurn() {
            Instant confirmedAt = CREATED.plus(Duration.ofDays(1));
            Instant revokedAt = CREATED.plus(Duration.ofDays(2));
            Accreditation s = AccreditationDecider.evolve(null, AccreditationDecider.submit(
                    ID, USER, AccreditationType.BY_INCOME, "abc123", "admin-1", CREATED, UUID.randomUUID()));

            s = apply(s, new Finalise(Outcome.CONFIRMED, "admin-2", OptionalLong.empty()), confirmedAt);
            s = apply(s, new Finalise(Outcome.FAILED, "admin-3", OptionalLong.empty()), revokedAt);

            // Creation fields are kept; status, version and the last update follow each event
            assertThat(s).isEqualTo(new Accreditation(
                    ID, USER, AccreditationType.BY_INCOME, FAILED, 3, CREATED, revokedAt, "admin-3"));
        }

        private Accreditation apply(Accreditation s, AccreditationCommand command, Instant at) {
            var accepted = (Accepted) AccreditationDecider.decide(s, command, at, UUID.randomUUID());
            return AccreditationDecider.evolve(s, accepted.event());
        }
    }
}
