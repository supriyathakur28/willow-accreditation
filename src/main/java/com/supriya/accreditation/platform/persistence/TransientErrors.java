package com.supriya.accreditation.platform.persistence;

import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

/** Database failures that are worth retrying later: the request itself was fine. */
public final class TransientErrors {

    static final int ER_LOCK_DEADLOCK = 1213;
    static final int ER_LOCK_WAIT_TIMEOUT = 1205;

    private TransientErrors() {
    }

    /** Lock waits and deadlocks, pool timeouts and lost connections. */
    public static boolean isTransient(final Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            // SQLTransientException covers 1205, 1213 and the pool's connection timeout
            if (t instanceof SQLTransientException || t instanceof SQLRecoverableException) {
                return true;
            }
        }
        return false;
    }

    public static boolean isDeadlock(final Throwable error) {
        return hasErrorCode(error, ER_LOCK_DEADLOCK);
    }

    private static boolean hasErrorCode(
            final Throwable error,
            final int code) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && sql.getErrorCode() == code) {
                return true;
            }
        }
        return false;
    }
}
