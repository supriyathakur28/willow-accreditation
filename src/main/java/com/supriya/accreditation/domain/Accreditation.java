package com.supriya.accreditation.domain;

import java.time.Instant;
import java.util.UUID;

/** Current state of one accreditation in the admin write model. Immutable; changed only through {@link AccreditationDecider#evolve}. */
public record Accreditation(
        UUID id,
        String userId,
        AccreditationType type,
        AccreditationStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        String updatedBy) {
}
