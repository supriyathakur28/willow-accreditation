package com.supriya.accreditation.domain;

/** The statuses an admin (or the expiry job) can move an accreditation to. PENDING is never an outcome. */
public enum Outcome {
    CONFIRMED,
    EXPIRED,
    FAILED;

    public AccreditationStatus status() {
        return switch (this) {
            case CONFIRMED -> AccreditationStatus.CONFIRMED;
            case EXPIRED -> AccreditationStatus.EXPIRED;
            case FAILED -> AccreditationStatus.FAILED;
        };
    }
}
