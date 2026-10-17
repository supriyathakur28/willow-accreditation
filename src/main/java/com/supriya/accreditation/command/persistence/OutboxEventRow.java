package com.supriya.accreditation.command.persistence;

import com.supriya.accreditation.platform.UuidBinaryConverter;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.data.model.DataType;

import java.time.Instant;
import java.util.UUID;

/** An event waiting for (or already through) the relay. {@code id} gives the publish order. */
@MappedEntity("outbox_events")
public record OutboxEventRow(
        @Id @GeneratedValue @Nullable Long id,
        @MappedProperty(converter = UuidBinaryConverter.class) @TypeDef(type = DataType.BYTE_ARRAY) UUID eventId,
        @MappedProperty(converter = UuidBinaryConverter.class) @TypeDef(type = DataType.BYTE_ARRAY) UUID aggregateId,
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
