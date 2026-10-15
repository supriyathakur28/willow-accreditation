package com.supriya.accreditation.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.supriya.accreditation.domain.AccreditationEvent;
import com.supriya.accreditation.domain.AccreditationEvent.AccreditationConfirmed;
import com.supriya.accreditation.domain.AccreditationEvent.AccreditationExpired;
import com.supriya.accreditation.domain.AccreditationEvent.AccreditationFailed;
import com.supriya.accreditation.domain.AccreditationEvent.AccreditationSubmitted;
import com.supriya.accreditation.domain.AccreditationStatus;
import com.supriya.accreditation.domain.AccreditationType;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

/**
 * The wire format of {@code accreditation.events.v1}: versioned JSON, snake_case, additive changes only.
 * The write side produces it and the read side consumes it; they share nothing else.
 */
@Serdeable
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccreditationEventMessage(
        UUID eventId,
        String eventType,
        int schemaVersion,
        UUID accreditationId,
        String userId,
        AccreditationType accreditationType,
        @Nullable AccreditationStatus previousStatus,
        AccreditationStatus status,
        long version,
        Instant submittedAt,
        Instant occurredAt,
        String performedBy,
        @Nullable String documentSha256) {

    public static AccreditationEventMessage from(AccreditationEvent event) {
        String documentSha256 = event instanceof AccreditationSubmitted submitted ? submitted.documentSha256() : null;
        return new AccreditationEventMessage(event.eventId(), event.eventType(), AccreditationEvent.SCHEMA_VERSION,
                event.accreditationId(), event.userId(), event.accreditationType(), event.previousStatus(),
                event.status(), event.version(), event.submittedAt(), event.occurredAt(), event.performedBy(),
                documentSha256);
    }

    /** @throws IllegalArgumentException for an event type this version does not know (a poison record) */
    public AccreditationEvent toDomain() {
        return switch (eventType) {
            case "AccreditationSubmitted" -> new AccreditationSubmitted(eventId, accreditationId, userId,
                    accreditationType, version, submittedAt, occurredAt, performedBy, documentSha256);
            case "AccreditationConfirmed" -> new AccreditationConfirmed(eventId, accreditationId, userId,
                    accreditationType, previousStatus, version, submittedAt, occurredAt, performedBy);
            case "AccreditationFailed" -> new AccreditationFailed(eventId, accreditationId, userId,
                    accreditationType, previousStatus, version, submittedAt, occurredAt, performedBy);
            case "AccreditationExpired" -> new AccreditationExpired(eventId, accreditationId, userId,
                    accreditationType, previousStatus, version, submittedAt, occurredAt, performedBy);
            default -> throw new IllegalArgumentException("Unknown event type " + eventType);
        };
    }
}
