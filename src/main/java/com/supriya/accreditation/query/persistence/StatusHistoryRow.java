package com.supriya.accreditation.query.persistence;

import com.supriya.accreditation.domain.AccreditationStatus;
import com.supriya.accreditation.platform.UuidBinaryConverter;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.annotation.TypeDef;
import io.micronaut.data.model.DataType;

import java.time.Instant;
import java.util.UUID;

/** One audit entry: a single state change of one accreditation. */
@MappedEntity("accreditation_status_history")
public record StatusHistoryRow(
        @Id @MappedProperty(converter = UuidBinaryConverter.class) @TypeDef(type = DataType.BYTE_ARRAY) UUID eventId,
        @MappedProperty(converter = UuidBinaryConverter.class) @TypeDef(type = DataType.BYTE_ARRAY) UUID accreditationId,
        long version,
        String eventType,
        @Nullable AccreditationStatus fromStatus,
        AccreditationStatus toStatus,
        String performedBy,
        Instant occurredAt) {
}
