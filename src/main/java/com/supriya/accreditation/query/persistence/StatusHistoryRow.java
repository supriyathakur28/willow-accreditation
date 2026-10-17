package com.supriya.accreditation.query.persistence;

import com.supriya.accreditation.domain.AccreditationStatus;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;
import java.util.UUID;

/** One audit entry: a single state change of one accreditation. */
@MappedEntity("accreditation_status_history")
public record StatusHistoryRow(
        @Id UUID eventId,
        UUID accreditationId,
        long version,
        String eventType,
        @Nullable AccreditationStatus fromStatus,
        AccreditationStatus toStatus,
        String performedBy,
        Instant occurredAt) {
}
