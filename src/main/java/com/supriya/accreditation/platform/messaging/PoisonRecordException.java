package com.supriya.accreditation.platform.messaging;

/** The record itself is unusable (unparseable, unknown type, contradicts stored data): retrying cannot help. */
public class PoisonRecordException extends RuntimeException {

    public PoisonRecordException(final String message) {
        super(message);
    }

    public PoisonRecordException(
            final String message,
            final Throwable cause) {
        super(message, cause);
    }
}
