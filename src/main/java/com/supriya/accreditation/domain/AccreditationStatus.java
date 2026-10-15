package com.supriya.accreditation.domain;

/**
 * Lifecycle of an accreditation.
 * <pre>
 *   PENDING   -> CONFIRMED | FAILED | EXPIRED
 *   CONFIRMED -> EXPIRED | FAILED (revocation)
 *   FAILED, EXPIRED: terminal
 * </pre>
 */
public enum AccreditationStatus {
    PENDING,
    CONFIRMED,
    EXPIRED,
    FAILED;

    public boolean isTerminal() {
        return this == EXPIRED || this == FAILED;
    }

    public boolean canMoveTo(AccreditationStatus target) {
        return switch (this) {
            case PENDING -> target == CONFIRMED || target == FAILED || target == EXPIRED;
            case CONFIRMED -> target == EXPIRED || target == FAILED;
            case EXPIRED, FAILED -> false;
        };
    }
}
