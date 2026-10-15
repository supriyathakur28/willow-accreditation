package com.supriya.accreditation.platform.messaging;

/** The record itself is unusable (unparseable, unknown type, contradicts stored data): retrying cannot help. */
public class PoisonRecordException extends RuntimeException {

    public PoisonRecordException(String message) {
        super(message);
    }

    public PoisonRecordException(String message, Throwable cause) {
        super(message, cause);
    }
}
