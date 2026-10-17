package com.supriya.accreditation.platform.persistence;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Optional;

/**
 * Tells whether an insert hit a unique key, and which one. A duplicate is often an expected outcome (a redelivered
 * event), so it must be told apart from other errors, and sometimes one key from another: the history consumer
 * ignores a repeated event ID but treats a repeated version under a new event ID as poison.
 */
public final class DuplicateKeys {

    private static final int ER_DUP_ENTRY = 1062;
    private static final String MARKER = "for key '";

    private DuplicateKeys() {
    }

    /** The violated key's name without the table prefix, e.g. {@code uq_accreditations_idempotency_key}. */
    public static Optional<String> violatedKey(final Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLIntegrityConstraintViolationException sql && sql.getErrorCode() == ER_DUP_ENTRY) {
                return keyName(sql.getMessage());
            }
        }
        return Optional.empty();
    }

    // MySQL 8: "Duplicate entry 'x' for key 'accreditations.uq_accreditations_idempotency_key'"
    private static Optional<String> keyName(final String message) {
        if (message == null) {
            return Optional.empty();
        }
        final int start = message.lastIndexOf(MARKER);
        final int end = message.lastIndexOf('\'');
        if (start < 0 || end <= start + MARKER.length()) {
            return Optional.empty();
        }
        final String qualified = message.substring(start + MARKER.length(), end);
        return Optional.of(qualified.substring(qualified.lastIndexOf('.') + 1));
    }
}
