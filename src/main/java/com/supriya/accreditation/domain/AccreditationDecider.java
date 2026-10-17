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

import java.time.Instant;
import java.util.UUID;

import static com.supriya.accreditation.domain.Decision.Reason.NOT_ELIGIBLE_FOR_EXPIRY;
import static com.supriya.accreditation.domain.Decision.Reason.TRANSITION_NOT_ALLOWED;
import static com.supriya.accreditation.domain.Decision.Reason.VERSION_MISMATCH;

/**
 * The business rules, as pure functions: no clock, no database, no exceptions.
 * Time and IDs are passed in, so the same inputs always give the same result.
 * Rule 4c (one PENDING per user) is not here: it spans rows and is enforced by a unique index.
 */
public final class AccreditationDecider {

    public static final String EXPIRY_JOB_ACTOR = "system:expiry-job";

    private AccreditationDecider() {
    }

    /** A new accreditation always starts PENDING at version 1. */
    public static AccreditationSubmitted submit(
            final UUID accreditationId,
            final String userId,
            final AccreditationType type,
            final String documentSha256,
            final String actor,
            final Instant now,
            final UUID eventId) {
        return new AccreditationSubmitted(eventId, accreditationId, userId, type, 1, now, now, actor, documentSha256);
    }

    /** Decides a command against the current (locked) state. */
    public static Decision decide(
            final Accreditation state,
            final AccreditationCommand command,
            final Instant now,
            final UUID eventId) {
        return switch (command) {
            case Finalise finalise -> finalise(state, finalise, now, eventId);
            case ExpireIfStale expire -> expireIfStale(state, expire, now, eventId);
        };
    }

    /** Applies an accepted event to the write-side state. */
    public static Accreditation evolve(
            final Accreditation state,
            final AccreditationEvent event) {
        return switch (event) {
            case AccreditationSubmitted submitted -> new Accreditation(
                    submitted.accreditationId(), submitted.userId(), submitted.accreditationType(),
                    AccreditationStatus.PENDING, submitted.version(),
                    submitted.submittedAt(), submitted.occurredAt(), submitted.performedBy());
            case AccreditationConfirmed changed -> applyChange(state, changed);
            case AccreditationFailed changed -> applyChange(state, changed);
            case AccreditationExpired changed -> applyChange(state, changed);
        };
    }

    private static Decision finalise(
            final Accreditation state,
            final Finalise command,
            final Instant now,
            final UUID eventId) {
        final AccreditationStatus target = command.outcome().status();
        // Re-sending the current status changes nothing, in any state, so a retried PUT gets the same 200.
        if (state.status() == target) {
            return new NoOp(state);
        }
        if (command.expectedVersion().isPresent() && command.expectedVersion().getAsLong() != state.version()) {
            return new Rejected(VERSION_MISMATCH, state);
        }
        if (!state.status().canMoveTo(target)) {
            return new Rejected(TRANSITION_NOT_ALLOWED, state);
        }
        return new Accepted(transition(state, command.outcome(), command.actor(), now, eventId));
    }

    private static Decision expireIfStale(
            final Accreditation state,
            final ExpireIfStale command,
            final Instant now,
            final UUID eventId) {
        // Eligibility is re-checked on the locked row, never on the values the job scanned earlier.
        final boolean eligible = state.status() == AccreditationStatus.CONFIRMED
                && state.updatedAt().isBefore(command.cutoff());
        if (!eligible) {
            return new Rejected(NOT_ELIGIBLE_FOR_EXPIRY, state);
        }
        return new Accepted(transition(state, Outcome.EXPIRED, command.actor(), now, eventId));
    }

    private static AccreditationEvent transition(
            final Accreditation state,
            final Outcome outcome,
            final String actor,
            final Instant now,
            final UUID eventId) {
        final long version = state.version() + 1;
        return switch (outcome) {
            case CONFIRMED -> new AccreditationConfirmed(eventId, state.id(), state.userId(), state.type(),
                    state.status(), version, state.createdAt(), now, actor);
            case FAILED -> new AccreditationFailed(eventId, state.id(), state.userId(), state.type(),
                    state.status(), version, state.createdAt(), now, actor);
            case EXPIRED -> new AccreditationExpired(eventId, state.id(), state.userId(), state.type(),
                    state.status(), version, state.createdAt(), now, actor);
        };
    }

    private static Accreditation applyChange(
            final Accreditation state,
            final AccreditationEvent event) {
        return new Accreditation(state.id(), state.userId(), state.type(), event.status(), event.version(),
                state.createdAt(), event.occurredAt(), event.performedBy());
    }
}
