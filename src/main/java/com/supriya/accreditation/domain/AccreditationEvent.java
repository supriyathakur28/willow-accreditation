package com.supriya.accreditation.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Facts about an accreditation, published to {@code accreditation.events.v1}.
 * Every event carries a full snapshot (user, type, status, version, submittedAt), so a consumer can
 * build or repair its row from any single event, even when events arrive out of order.
 */
public sealed interface AccreditationEvent permits AccreditationEvent.AccreditationSubmitted,
        AccreditationEvent.AccreditationConfirmed, AccreditationEvent.AccreditationFailed,
        AccreditationEvent.AccreditationExpired {

    int SCHEMA_VERSION = 1;

    UUID eventId();

    UUID accreditationId();

    String userId();

    AccreditationType accreditationType();

    /** {@code null} only for {@link AccreditationSubmitted}. */
    AccreditationStatus previousStatus();

    AccreditationStatus status();

    long version();

    Instant submittedAt();

    Instant occurredAt();

    String performedBy();

    default String eventType() {
        return getClass().getSimpleName();
    }

    record AccreditationSubmitted(
            UUID eventId,
            UUID accreditationId,
            String userId,
            AccreditationType accreditationType,
            long version,
            Instant submittedAt,
            Instant occurredAt,
            String performedBy,
            String documentSha256) implements AccreditationEvent {

        @Override
        public AccreditationStatus previousStatus() {
            return null;
        }

        @Override
        public AccreditationStatus status() {
            return AccreditationStatus.PENDING;
        }
    }

    record AccreditationConfirmed(
            UUID eventId,
            UUID accreditationId,
            String userId,
            AccreditationType accreditationType,
            AccreditationStatus previousStatus,
            long version,
            Instant submittedAt,
            Instant occurredAt,
            String performedBy) implements AccreditationEvent {

        @Override
        public AccreditationStatus status() {
            return AccreditationStatus.CONFIRMED;
        }
    }

    record AccreditationFailed(
            UUID eventId,
            UUID accreditationId,
            String userId,
            AccreditationType accreditationType,
            AccreditationStatus previousStatus,
            long version,
            Instant submittedAt,
            Instant occurredAt,
            String performedBy) implements AccreditationEvent {

        @Override
        public AccreditationStatus status() {
            return AccreditationStatus.FAILED;
        }
    }

    record AccreditationExpired(
            UUID eventId,
            UUID accreditationId,
            String userId,
            AccreditationType accreditationType,
            AccreditationStatus previousStatus,
            long version,
            Instant submittedAt,
            Instant occurredAt,
            String performedBy) implements AccreditationEvent {

        @Override
        public AccreditationStatus status() {
            return AccreditationStatus.EXPIRED;
        }
    }
}
