package com.supriya.accreditation.platform;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Optional;

/**
 * Tells which unique key an insert hit. Callers react per key (for example idempotency key vs one-PENDING rule),
 * so catching "a duplicate" is not enough.
 */
public final class DuplicateKeys {

    private static final int ER_DUP_ENTRY = 1062;
    private static final String MARKER = "for key '";

    private DuplicateKeys() {
    }

    /** The violated key's name without the table prefix, e.g. {@code uq_accreditations_idempotency_key}. */
    public static Optional<String> violatedKey(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLIntegrityConstraintViolationException sql && sql.getErrorCode() == ER_DUP_ENTRY) {
                return keyName(sql.getMessage());
            }
        }
        return Optional.empty();
    }

    public static boolean isViolationOf(Throwable error, String keyName) {
        return violatedKey(error).filter(keyName::equals).isPresent();
    }

    // MySQL 8: "Duplicate entry 'x' for key 'accreditations.uq_accreditations_idempotency_key'"
    private static Optional<String> keyName(String message) {
        if (message == null) {
            return Optional.empty();
        }
        int start = message.lastIndexOf(MARKER);
        int end = message.lastIndexOf('\'');
        if (start < 0 || end <= start + MARKER.length()) {
            return Optional.empty();
        }
        String qualified = message.substring(start + MARKER.length(), end);
        return Optional.of(qualified.substring(qualified.lastIndexOf('.') + 1));
    }
}
