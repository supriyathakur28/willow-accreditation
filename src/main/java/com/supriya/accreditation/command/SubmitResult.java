package com.supriya.accreditation.command;

import java.util.UUID;

/** What a submission produced. Business outcomes are values; the controller maps them to status codes. */
public sealed interface SubmitResult {

    /** Created now, or an earlier request with the same Idempotency-Key and body created it. */
    record Created(UUID accreditationId) implements SubmitResult {
    }

    record UserNotFound() implements SubmitResult {
    }

    record PendingExists() implements SubmitResult {
    }

    /** The Idempotency-Key was used before with a different body (caught by the database when Redis missed it). */
    record KeyReused() implements SubmitResult {
    }
}
