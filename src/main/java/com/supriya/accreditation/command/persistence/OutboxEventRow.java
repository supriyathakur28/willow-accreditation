package com.supriya.accreditation.command.persistence;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;

import java.time.Instant;
import java.util.UUID;

/** An event waiting for (or already through) the relay. {@code id} gives the publish order. */
@MappedEntity("outbox_events")
public record OutboxEventRow(
        @Id @GeneratedValue @Nullable Long id,
        UUID eventId,
        UUID aggregateId,
        String eventType,
        String payload,
        @Nullable String requestId,
        Instant createdAt,
        @Nullable Instant publishedAt) {

    public static OutboxEventRow pending(
            final UUID eventId,
            final UUID aggregateId,
            final String eventType,
            final String payload,
            @Nullable final String requestId,
            final Instant createdAt) {
        return new OutboxEventRow(null, eventId, aggregateId, eventType, payload, requestId, createdAt, null);
    }
}
